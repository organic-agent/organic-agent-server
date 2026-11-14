package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentActor
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component

/** 조건에 맞는 사진을 고른다. 화면 필터·집계·실행 계획이 모두 이 한 곳의 해석을 쓴다. */
@Component
class AgentPhotoFinder {

    companion object {

        /** categorize가 연사 묶음 안에서 가장 나은 컷에 매기는 순위. */
        const val BURST_REPRESENTATIVE_RANK = 0
    }

    /** 나·배우자 조건은 [snapshot]의 묻는 사람 기준으로 푼다. */
    fun find(snapshot: AgentGallerySnapshotDto, filter: PhotoFilter): List<AgentPhotoRow> {
        val actors = mapOf(
            AgentActor.ME to setOf(snapshot.viewerUserId),
            AgentActor.PARTNER to snapshot.partnerUserIds,
        )
        return snapshot.photos.filter { photo -> matches(photo, filter) && matchesActors(photo, filter, actors) }
    }

    private fun matches(photo: AgentPhotoRow, filter: PhotoFilter): Boolean {
        if (filter.hasFolder() && !inFolder(photo, filter)) return false
        if (filter.subjects.isNotEmpty() && photo.subject !in filter.subjects) return false
        if (filter.selected != null && photo.selected != filter.selected) return false
        if (filter.orientation != null && photo.orientation != filter.orientation) return false
        if (filter.burstRepresentativeOnly && photo.burstRank != BURST_REPRESENTATIVE_RANK) return false
        if (filter.likedByGuests != null && (photo.likeCount > 0) != filter.likedByGuests) return false
        if (filter.hasComments != null && (photo.commentCount > 0) != filter.hasComments) return false
        if (filter.minRating != null && (photo.rating == null || photo.rating < filter.minRating)) return false
        if (filter.maxRating != null && (photo.rating == null || photo.rating > filter.maxRating)) return false
        if (filter.hasMemo != null && photo.hasMemo != filter.hasMemo) return false
        return true
    }

    private fun matchesActors(photo: AgentPhotoRow, filter: PhotoFilter, actors: Map<AgentActor, Set<Long>>): Boolean {
        fun did(actor: AgentActor?, userId: Long?): Boolean = actor == null || (userId != null && userId in actors.getValue(actor))

        if (!did(filter.addedBy, photo.addedByUserId)) return false
        if (!did(filter.ratedBy, photo.ratedByUserId)) return false
        if (!did(filter.memoBy, photo.memoByUserId)) return false
        val commenter = filter.commentedBy ?: return true
        return photo.commentAuthorIds.any { it in actors.getValue(commenter) }
    }

    private fun inFolder(photo: AgentPhotoRow, filter: PhotoFilter): Boolean =
        photo.conceptFolderId in filter.conceptFolderIds || photo.detailFolderId in filter.detailFolderIds
}
