/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import timber.log.Timber
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val fontSize by AppPrefs.getInstance().keyboard.horizontalCandidateFontSize
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    // The native bulk candidate list only delivers the first 16 candidates.
    // Load the rest on demand as the user scrolls the bar.
    private var loadMoreJob: Job? = null
    private var candidateGeneration = 0
    private var loadExhausted = false
    // Loaded page waiting for the scroll to settle. Inserting items in the
    // middle of a gesture forces a full Flexbox relayout and drops frames.
    private var pendingCandidates: Array<CandidateWord>? = null

    // Tells the expanded candidate window to reload from the first candidate.
    // Replay the last value so a newly opened window gets it as well.
    private val _expandedCandidatesRefresh = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidatesRefresh = _expandedCandidatesRefresh.asSharedFlow()

    private fun refreshExpanded() {
        // The bar can scroll now, so the expand button must not disappear when
        // more candidates become laid out. It is only hidden if there is
        // nothing to expand at all.
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to adapter.candidates.isEmpty()
        )
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme, fontSize.toFloat()) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                    // Never squeeze a candidate below its natural width. The bar
                    // scrolls instead, so long candidates keep the same font size.
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    fcitx.launchOnReady { it.select(holder.idx) }
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            init {
                // Lay everything out in one horizontal row. The bar's height is
                // fixed, so the default WRAP would put the overflow on invisible
                // lines that can never be reached.
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.NOWRAP
            }

            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = true
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.candidates.size) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded()
            }
            // no need to override `generate{,Default}LayoutParams`, because HorizontalCandidateViewAdapter
            // guarantees ViewHolder's layoutParams to be `FlexboxLayoutManager.LayoutParams`
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
            // The bar's size is fixed by its constraints, not by its contents.
            setHasFixedSize(true)
            // Keep more item views around to avoid re-inflating while swiping.
            setItemViewCacheSize(16)
            // Android 12+ stretch overscroll offsets the drawn content without
            // offsetting touch targets; horizontal scrolling is enabled here, so
            // disable it to keep candidate taps accurate.
            overScrollMode = View.OVER_SCROLL_NEVER
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx > 0) maybeLoadMore()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        recyclerView.post { appendPendingCandidates() }
                    }
                }
            })
        }
    }

    private fun maybeLoadMore() {
        // A fetched page that is waiting for the scroll to settle already
        // covers the next offset; don't fetch it twice.
        if (pendingCandidates != null || loadMoreJob?.isActive == true || loadExhausted) return
        val loaded = adapter.candidates.size
        if (loaded == 0) return
        val total = adapter.total
        if (total >= 0 && loaded >= total) return
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (lastVisible < 0 || lastVisible < loaded - LOAD_MORE_THRESHOLD) return

        val generation = candidateGeneration
        val offset = loaded
        loadMoreJob = service.lifecycleScope.launch {
            val more = try {
                fcitx.runOnReady { getCandidates(offset, LOAD_MORE_BATCH_SIZE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to load more candidates (offset=$offset)")
                return@launch
            }
            // Candidate list changed while we were loading, drop the result.
            if (generation != candidateGeneration) return@launch
            if (more.isEmpty()) {
                loadExhausted = true
                return@launch
            }
            if (more.size < LOAD_MORE_BATCH_SIZE) {
                loadExhausted = true
            }
            if (view.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                adapter.appendCandidates(more)
                if (!loadExhausted && !view.canScrollHorizontally(1)) {
                    // Wide screen: the appended page still doesn't overflow the bar.
                    loadMoreJob = null
                    view.post { maybeLoadMore() }
                }
            } else {
                // Do not insert while the user is dragging/flinging: the full
                // Flexbox relayout would drop frames in the middle of a gesture.
                pendingCandidates = more
            }
        }
    }

    private fun appendPendingCandidates() {
        val pending = pendingCandidates ?: return
        pendingCandidates = null
        adapter.appendCandidates(pending)
        if (!loadExhausted && !view.canScrollHorizontally(1)) {
            // Still not scrollable, e.g. on a very wide screen.
            loadMoreJob = null
            view.post { maybeLoadMore() }
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        candidateGeneration++
        loadMoreJob?.cancel()
        loadMoreJob = null
        loadExhausted = false
        pendingCandidates = null
        // Pick up font-size changes on the next candidate update.
        adapter.textSizeSp = fontSize.toFloat()
        val candidates = data.candidates
        val total = data.total
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        adapter.updateCandidates(candidates, total)
        // The expanded list always starts from the first candidate and must not
        // be affected by how far the horizontal bar has been scrolled.
        _expandedCandidatesRefresh.tryEmit(Unit)
        view.scrollToPosition(0)
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded()
        } else {
            // On wide screens the first batch may not overflow the bar at all.
            view.post { maybeLoadMore() }
        }
    }

    companion object {
        private const val LOAD_MORE_THRESHOLD = 4
        private const val LOAD_MORE_BATCH_SIZE = 32
    }
}
