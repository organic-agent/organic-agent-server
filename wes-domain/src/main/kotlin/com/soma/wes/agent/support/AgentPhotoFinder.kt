package com.soma.wes.agent.support

import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component

/** 조건에 맞는 사진을 고른다. 화면 필터·집계·실행 계획이 모두 이 한 곳의 해석을 쓴다. */
@Component
class AgentPhotoFinder {

    companion object {

        /** categorize가 연사 묶음 안에서 가장 나은 컷에 매기는 순위. */
        const val BURST_REPRESENTATIVE_RANK = 0
    }

    fun find(photos: List<AgentPhotoRow>, filter: PhotoFilter): List<AgentPhotoRow> =
        photos.filter { photo -> matches(photo, filter) }

    private fun matches(photo: AgentPhotoRow, filter: PhotoFilter): Boolean {
        if (filter.hasFolder() && !inFolder(photo, filter)) return false
        if (filter.subjects.isNotEmpty() && photo.subject !in filter.subjects) return false
        if (filter.selected != null && photo.selected != filter.selected) return false
        if (filter.orientation != null && photo.orientation != filter.orientation) return false
        if (filter.burstRepresentativeOnly && photo.burstRank != BURST_REPRESENTATIVE_RANK) return false
        return true
    }

    private fun inFolder(photo: AgentPhotoRow, filter: PhotoFilter): Boolean =
        photo.conceptFolderId in filter.conceptFolderIds || photo.detailFolderId in filter.detailFolderIds
}
