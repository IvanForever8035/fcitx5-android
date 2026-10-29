/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp
import kotlin.math.max

class ResponsiveThemeListView(context: Context) : RecyclerView(context) {

    init {
        // Android 12+ stretch overscroll offsets the drawn content without
        // offsetting touch targets, which makes taps miss after over-scrolling.
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    var itemWidth = dp(128)
    var itemHeight = dp(92)
    var minMargin = dp(16)

    private lateinit var grid: GridLayoutManager

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val spanCount = max(1, estimateSpanCount(w))
        if (!this::grid.isInitialized) {
            grid = object : GridLayoutManager(context, spanCount) {
                override fun generateDefaultLayoutParams() = LayoutParams(itemWidth, itemHeight)
            }
            layoutManager = grid
            addItemDecoration(ThemeListItemDecoration(itemWidth, spanCount))
        } else {
            grid.spanCount = spanCount
            invalidateItemDecorations()
        }
    }

    private fun estimateSpanCount(width: Int) = (width - minMargin) / (itemWidth + minMargin)
}
