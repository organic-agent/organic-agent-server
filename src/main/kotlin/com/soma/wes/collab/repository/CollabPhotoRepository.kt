package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabPhoto
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CollabPhotoRepository : JpaRepository<CollabPhoto, Long> {

    /** 담은 순서대로. 하객은 부부가 보여주고 싶은 순서로 넘겨보게 된다. */
    fun findAllByCollabSessionIdOrderByIdAsc(collabSessionId: Long, pageable: Pageable): Page<CollabPhoto>

    fun findAllByCollabSessionId(collabSessionId: Long): List<CollabPhoto>

    /** 이 세션에 담긴 사진인지까지 함께 본다. 세션을 안 보면 남의 세션 사진에 댓글을 달 수 있다. */
    fun findByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto?

    /**
     * 반응을 남길 때 쓴다. 사진 행을 잠그고 찾는다.
     *
     * 반응은 하객당 한 행이라 "없으면 만들고 있으면 고친다"인데, 같은 하객의 요청 둘이
     * 겹치면(버튼 연타·재시도) 둘 다 "아직 없다"를 읽고 각자 INSERT 한다. 유니크 제약이
     * 막아주긴 하지만 그때는 한쪽 요청이 통째로 500이 된다. 반응 행은 아직 없을 수 있어 잠글
     * 대상이 못 되므로, 그 반응이 매달릴 사진 행을 잠근다 — 별점이 [com.soma.wes.photo.repository.PhotoRepository.findWithLockByIdAndGalleryId]로
     * 하는 것과 같은 방식이다. 같은 사진에 동시에 반응한 하객들이 이 잠금에서 줄을 서지만,
     * 반응 한 번은 짧은 트랜잭션이라 줄이 길어지지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto?

    fun countByCollabSessionId(collabSessionId: Long): Long

    /**
     * 세션마다 사진이 몇 장인지. 링크 목록 화면이 세션 수만큼 세지 않도록 한 번에 읽는다.
     *
     * 사진이 하나도 없는 세션은 결과에 아예 없다. 호출부가 0으로 채운다 — 폴더 없이 연 세션은
     * 사진을 담기 전까지 그 상태다.
     */
    @Query(
        """
        SELECT cp.collabSessionId AS collabSessionId, COUNT(cp) AS count
        FROM CollabPhoto cp
        WHERE cp.collabSessionId IN :collabSessionIds
        GROUP BY cp.collabSessionId
        """,
    )
    fun countByCollabSessionIdIn(
        @Param("collabSessionIds") collabSessionIds: Collection<Long>,
    ): List<CollabSessionPhotoCountProjection>

    /**
     * 사진을 빼낸다. 댓글·반응은 DB의 `ON DELETE CASCADE`가 함께 지운다 —
     * 세션에서 뺀다는 것은 "이 사진은 더 묻지 않겠다"는 뜻이라 그때 받은 의견도 함께 사라진다.
     *
     * 파생 삭제로 두면 Spring Data가 대상을 전부 조회한 뒤 한 건씩 지운다. 한 번에 수십 장을
     * 빼는 화면이라 벌크 삭제로 못박는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM CollabPhoto cp WHERE cp.collabSessionId = :collabSessionId AND cp.photoId IN :photoIds")
    fun deleteAllByCollabSessionIdAndPhotoIdIn(
        @Param("collabSessionId") collabSessionId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
    ): Int
}
