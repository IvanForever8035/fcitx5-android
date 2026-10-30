/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    init {
        setHasStableIds(true)
    }

    var candidates: Array<CandidateWord> = arrayOf()
        private set

    var total = -1
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(data: Array<CandidateWord>, total: Int) {
        this.candidates = data
        this.total = total
        notifyDataSetChanged()
    }

    /**
     * Append a page of candidates fetched on demand, so the horizontal bar can
     * scroll past the initial batch (native bulk mode sends at most 16).
     */
    fun appendCandidates(data: Array<CandidateWord>) {
        if (data.isEmpty()) return
        val start = candidates.size
        candidates = candidates + data
        notifyItemRangeInserted(start, data.size)
    }

    override fun getItemCount() = candidates.size

    override fun getItemId(position: Int) = candidates.getOrNull(position).hashCode().toLong()

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent).apply {
                // Candidate items size to their content; overflow is scrolled.
                flexShrink = 0f
            }
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        holder.update(position, candidates[position])
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}
