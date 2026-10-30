/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection

/**
 * A candidate plus its index in the full candidate list. The index is needed
 * because Paging only keeps a window of pages in the adapter, so the adapter
 * position is not the global candidate index.
 */
data class IndexedCandidate(val index: Int, val candidate: CandidateWord)

/**
 * Paging source for the horizontal candidate bar. Paging loads pages as the
 * user scrolls and [androidx.paging.PagingConfig.maxSize] bounds how many
 * candidates are kept in memory.
 */
class HorizontalCandidatesPagingSource(
    private val fcitx: FcitxConnection,
    private val total: Int
) : PagingSource<Int, IndexedCandidate>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, IndexedCandidate> {
        val start = params.key ?: 0
        val limit = params.loadSize
        val candidates = fcitx.runOnReady { getCandidates(start, limit) }
        val items = candidates.mapIndexed { i, candidate ->
            IndexedCandidate(start + i, candidate)
        }
        val prevKey = if (start > 0) (start - limit).coerceAtLeast(0) else null
        val nextKey = if (total > 0) {
            if (start + limit >= total) null else start + limit
        } else {
            if (candidates.size < limit) null else start + limit
        }
        return LoadResult.Page(items, prevKey, nextKey)
    }

    // Always reload from the beginning.
    override fun getRefreshKey(state: PagingState<Int, IndexedCandidate>): Int? = null
}
