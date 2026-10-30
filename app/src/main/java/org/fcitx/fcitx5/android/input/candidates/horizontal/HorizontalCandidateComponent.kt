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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
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
import kotlin.math.max
import kotlin.math.min

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
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

    // Sliding window: only WINDOW_SIZE candidates are kept in the adapter at a
    // time. Scrolling towards an edge shifts the window and loads the next
    // slice instead of appending to an ever-growing list.
    private var windowStart = 0
    private var windowTotal = -1
    private var windowLoading = false
    private var windowGeneration = 0

    // Some engines emit candidate events with identical contents (for example
    // while selecting a candidate). Skip them so the bar is not scrolled back
    // to the beginning for no reason.
    private var lastCandidateData: FcitxEvent.CandidateListEvent.Data? = null

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
        object : HorizontalCandidateViewAdapter(theme) {
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
                    if (dx > 0) shiftWindow(forward = true)
                    else if (dx < 0) shiftWindow(forward = false)
                }
            })
        }
    }

    private fun shiftWindow(forward: Boolean) {
        if (windowLoading) return
        val count = adapter.candidates.size
        if (count == 0) return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible < 0 || lastVisible < 0) return

        if (forward) {
            if (lastVisible < count - SHIFT_THRESHOLD) return
            if (windowTotal >= 0 && windowStart + count >= windowTotal) return
            // Shift so the current first visible candidate becomes position 0.
            val shift = firstVisible.coerceAtLeast(1)
            fetchWindow(windowStart + shift, anchorPosition = 0)
        } else {
            if (firstVisible > SHIFT_THRESHOLD) return
            if (windowStart <= 0) return
            val shift = min(WINDOW_SIZE / 2, windowStart)
            fetchWindow(windowStart - shift, anchorPosition = firstVisible + shift)
        }
    }

    private fun fetchWindow(newStart: Int, anchorPosition: Int) {
        windowLoading = true
        val generation = windowGeneration
        service.lifecycleScope.launch {
            val page = try {
                fcitx.runOnReady { getCandidates(newStart, WINDOW_SIZE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                windowLoading = false
                return@launch
            }
            if (generation != windowGeneration) return@launch
            if (page.isEmpty()) {
                if (newStart > windowStart) {
                    // Reached the end of the full candidate list.
                    windowTotal = windowStart + adapter.candidates.size
                }
                windowLoading = false
                return@launch
            }
            if (newStart > windowStart && page.size < WINDOW_SIZE) {
                windowTotal = newStart + page.size
            }
            view.stopScroll()
            windowStart = newStart
            adapter.updateCandidates(page, adapter.total, newStart)
            view.scrollToPosition(anchorPosition.coerceAtLeast(0))
            windowLoading = false
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        // Ignore redundant events; otherwise they would reset the scroll.
        if (data == lastCandidateData) return
        lastCandidateData = data
        windowGeneration++
        windowLoading = false
        windowStart = 0
        windowTotal = data.total
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
        adapter.updateCandidates(candidates, total, 0)
        // The expanded list always starts from the first candidate and must not
        // be affected by how far the horizontal bar has been scrolled.
        _expandedCandidatesRefresh.tryEmit(Unit)
        view.scrollToPosition(0)
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded()
        }
    }

    companion object {
        private const val WINDOW_SIZE = 10
        private const val SHIFT_THRESHOLD = 2
    }
}
