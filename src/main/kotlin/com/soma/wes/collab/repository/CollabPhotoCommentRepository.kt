package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabPhotoComment
import com.soma.wes.collab.repository.projection.CollabCommentCountProjection
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CollabPhotoCommentRepository : JpaRepository<CollabPhotoComment, Long> {

    /** 최근에 쓴 것이 위로. 사진 아래 붙는 목록이라 새 말이 먼저 보여야 한다. */
    fun findAllByCollabPhotoIdOrderByIdDesc(collabPhotoId: Long, pageable: Pageable): Page<CollabPhotoComment>

    fun findAllByCollabPhotoIdInOrderByIdDesc(
        collabPhotoIds: Collection<Long>,
        pageable: Pageable,
    ): Page<CollabPhotoComment>

    /**
     * 사진마다 댓글이 몇 개인지. 목록 화면이 사진 수만큼 세지 않도록 한 번에 읽는다.
     *
     * 댓글이 하나도 없는 사진은 결과에 아예 없다. 호출부가 0으로 채운다.
     */
    @Query(
        """
        SELECT c.collabPhotoId AS collabPhotoId, COUNT(c) AS count
        FROM CollabPhotoComment c
        WHERE c.collabPhotoId IN :collabPhotoIds
        GROUP BY c.collabPhotoId
        """,
    )
    fun countByCollabPhotoIdIn(
        @Param("collabPhotoIds") collabPhotoIds: Collection<Long>,
    ): List<CollabCommentCountProjection>
}
