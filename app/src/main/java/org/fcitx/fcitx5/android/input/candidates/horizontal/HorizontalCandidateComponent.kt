/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
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
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0

    /** Full candidate count from the latest engine event (-1 if unknown). */
    @Volatile
    var total = -1
        private set

    /** Whether the latest engine event contained no candidates. */
    @Volatile
    var candidatesEmpty = true
        private set

    // Some engines emit candidate events with identical contents (for example
    // while selecting a candidate). Skip them so they don't reset the list.
    private var lastCandidateData: FcitxEvent.CandidateListEvent.Data? = null

    // Tells the expanded candidate window to reload from the first candidate.
    // Replay the last value so a newly opened window gets it as well.
    private val _expandedCandidatesRefresh = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidatesRefresh = _expandedCandidatesRefresh.asSharedFlow()

    private var submitJob: Job? = null

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = WINDOW_SIZE,
                initialLoadSize = WINDOW_SIZE,
                // Keep already loaded pages. The engine's total is unknown
                // (-1), so placeholders cannot be used to drop and reload the
                // earlier pages; dropping them would make scrolling back fail.
                enablePlaceholders = false,
                prefetchDistance = WINDOW_SIZE / 2
            ),
            pagingSourceFactory = {
                HorizontalCandidatesPagingSource(fcitx, total)
            }
        )
    }

    private fun refreshExpanded() {
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to candidatesEmpty
        )
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        HorizontalCandidateViewAdapter(
            theme,
            onCandidateClick = { item ->
                fcitx.launchOnReady { it.select(item.index) }
            },
            onCandidateLongClick = { item, view ->
                inputView.showCandidateActionMenu(item.index, item.candidate.text, view)
            },
            onBindItem = { itemView ->
                val lp = itemView.layoutParams as? RecyclerView.LayoutParams
                    ?: RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                // Keep a minimum width but let the item grow to its content so
                // AutoScaleTextView does not shrink long candidates.
                val minWidth = when (fillStyle) {
                    NeverFillWidth -> 0
                    AutoFillWidth -> {
                        if (layoutMinWidth > 0) layoutMinWidth
                        else view.width / maxSpanCountPref.getValue() - dividerDrawable.intrinsicWidth
                    }
                    AlwaysFillWidth -> {
                        val count = adapter.itemCount
                        if (count in 1 until maxSpanCountPref.getValue()) view.width / count
                        else 0
                    }
                }
                itemView.minimumWidth = max(minWidth, context.dp(40))
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                itemView.layoutParams = lp
            }
        )
    }

    val layoutManager: LinearLayoutManager by lazy {
        object : LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) {
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                refreshExpanded()
            }
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
            // The bar's size is fixed by its constraints, not by its contents.
            setHasFixedSize(true)
            // Keep more item views around to avoid re-inflating while swiping.
            setItemViewCacheSize(16)
            // Android 12+ stretch overscroll offsets the drawn content without
            // offsetting touch targets; horizontal scrolling is enabled here, so
            // disable it to keep candidate taps accurate.
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        submitJob = service.lifecycleScope.launch {
            candidatesPager.flow.collectLatest { adapter.submitData(it) }
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        // Ignore redundant events; otherwise they would reset the scroll.
        if (data == lastCandidateData) return
        lastCandidateData = data
        total = data.total
        candidatesEmpty = data.candidates.isEmpty()
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> layoutMinWidth = 0
            AutoFillWidth ->
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
            AlwaysFillWidth -> layoutMinWidth = 0
        }
        // Reload the paging source with the new candidate list. Paging keeps
        // only a bounded window of pages and loads more as the bar scrolls.
        adapter.refresh()
        // The expanded list always starts from the first candidate and must not
        // be affected by how far the horizontal bar has been scrolled.
        _expandedCandidatesRefresh.tryEmit(Unit)
        view.scrollToPosition(0)
        refreshExpanded()
    }

    companion object {
        private const val WINDOW_SIZE = 10
    }
}
