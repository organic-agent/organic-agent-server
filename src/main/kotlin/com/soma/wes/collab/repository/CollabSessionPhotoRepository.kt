package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSessionPhoto
import com.soma.wes.collab.repository.projection.CollabSharedPhotoProjection
import com.soma.wes.photo.domain.Photo
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface CollabSessionPhotoRepository : JpaRepository<CollabSessionPhoto, Long> {
    fun findAllByCollabSessionIdAndPhotoIdIn(collabSessionId: Long, photoIds: Collection<Long>): List<CollabSessionPhoto>

    /** 휴지통의 사진 → 세션 순서와 맞추고, 여러 사진은 ID 순서로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.galleryId = :galleryId and p.id in :photoIds order by p.id")
    fun findWithLockByGalleryIdAndIdIn(galleryId: Long, photoIds: Collection<Long>): List<Photo>

    /** 호출자가 세션을 잠그고 모든 요청 사진의 스코프를 확인한 뒤에만 제거한다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from CollabSessionPhoto p where p.collabSessionId = :sessionId and p.photoId in :photoIds")
    fun deleteMemberships(sessionId: Long, photoIds: Collection<Long>): Int

    /** 두 공유 방식 모두 현재 보이는 같은 갤러리의 업로드 완료 사진만 반환한다. */
    @Query(value = """
        SELECT s.id AS sessionId, p.id AS photoId
        FROM collab_sessions s
        JOIN galleries g ON g.id = s.gallery_id AND g.deleted_at IS NULL
        JOIN photos p ON p.gallery_id = s.gallery_id AND p.deleted_at IS NULL AND p.status <> 'PENDING'
        WHERE s.id IN (:sessionIds) AND s.deleted_at IS NULL
          AND (
            (s.concept_folder_id IS NULL AND EXISTS (
                SELECT 1 FROM collab_session_photos m
                WHERE m.collab_session_id = s.id AND m.gallery_id = s.gallery_id AND m.photo_id = p.id
            )) OR (s.concept_folder_id IS NOT NULL AND EXISTS (
                SELECT 1 FROM concept_folders c
                JOIN detail_folders d ON d.concept_folder_id = c.id AND d.deleted_at IS NULL
                JOIN detail_folder_assignments a ON a.detail_folder_id = d.id
                WHERE c.id = s.concept_folder_id AND c.gallery_id = s.gallery_id
                  AND c.deleted_at IS NULL AND a.photo_id = p.id
            ))
          )
        ORDER BY s.id, p.display_order, p.id
    """, nativeQuery = true)
    fun findSharedPhotos(sessionIds: Collection<Long>): List<CollabSharedPhotoProjection>
}
