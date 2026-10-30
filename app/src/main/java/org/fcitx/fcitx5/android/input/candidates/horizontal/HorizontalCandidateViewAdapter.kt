/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

class HorizontalCandidateViewAdapter(
    private val theme: Theme,
    private val onCandidateClick: (IndexedCandidate) -> Unit,
    private val onCandidateLongClick: (IndexedCandidate, View) -> Unit,
    private val onBindItem: (View) -> Unit
) : PagingDataAdapter<IndexedCandidate, CandidateViewHolder>(diffCallback) {

    companion object {
        /**
         * Always rebind: the global candidate index and the fill layout params
         * must be applied again when a page is loaded or dropped.
         */
        private val diffCallback = object : DiffUtil.ItemCallback<IndexedCandidate>() {
            override fun areItemsTheSame(oldItem: IndexedCandidate, newItem: IndexedCandidate) =
                false

            override fun areContentsTheSame(oldItem: IndexedCandidate, newItem: IndexedCandidate) =
                false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = RecyclerView.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val item = getItem(position) ?: return
        holder.update(item.index, item.candidate)
        holder.itemView.setOnClickListener { onCandidateClick(item) }
        holder.itemView.setOnLongClickListener {
            onCandidateLongClick(item, holder.ui.root)
            true
        }
        onBindItem(holder.itemView)
    }

    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
        holder.clear()
        super.onViewRecycled(holder)
    }
}
