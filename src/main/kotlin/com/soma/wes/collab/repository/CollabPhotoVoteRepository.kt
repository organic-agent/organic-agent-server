package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabPhotoVote
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CollabPhotoVoteRepository : JpaRepository<CollabPhotoVote, Long> {

    /** 이 하객이 이 사진에 이미 남긴 반응. 있으면 덮어쓰고 없으면 만든다. */
    fun findByCollabPhotoIdAndCollabGuestId(collabPhotoId: Long, collabGuestId: Long): CollabPhotoVote?

    fun deleteByCollabPhotoIdAndCollabGuestId(collabPhotoId: Long, collabGuestId: Long): Long

    /** 하객 자신이 어느 사진에 무엇을 눌렀는지. 화면이 자기가 누른 버튼을 켜둔 채로 그린다. */
    fun findAllByCollabPhotoIdInAndCollabGuestId(
        collabPhotoIds: Collection<Long>,
        collabGuestId: Long,
    ): List<CollabPhotoVote>

    /**
     * 사진마다 반응이 몇 개씩인지. 사진 하나에 세 줄(GOOD·SOSO·BAD)까지 나온다.
     *
     * 반응 종류별로 질의를 나누지 않는다 — 종류가 늘면 질의도 늘고, 그중 하나를 빠뜨리면
     * 그 반응만 화면에서 조용히 0이 된다. 아무도 누르지 않은 사진은 결과에 아예 없고,
     * 그 자리는 호출부가 0으로 채운다.
     */
    @Query(
        """
        SELECT v.collabPhotoId AS collabPhotoId, v.reaction AS reaction, COUNT(v) AS count
        FROM CollabPhotoVote v
        WHERE v.collabPhotoId IN :collabPhotoIds
        GROUP BY v.collabPhotoId, v.reaction
        """,
    )
    fun countByReaction(
        @Param("collabPhotoIds") collabPhotoIds: Collection<Long>,
    ): List<CollabReactionCountProjection>
}
