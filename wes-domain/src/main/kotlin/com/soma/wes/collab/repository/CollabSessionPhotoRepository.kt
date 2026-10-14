package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSessionPhoto
import com.soma.wes.collab.repository.projection.CollabSharedPhotoProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.ZonedDateTime

interface CollabSessionPhotoRepository : JpaRepository<CollabSessionPhoto, Long> {
    fun findAllByCollabSessionIdAndPhotoIdIn(collabSessionId: Long, photoIds: Collection<Long>): List<CollabSessionPhoto>

    /**
     * 공유할 수 있는 사진 id만 잠그고 돌려준다. 휴지통의 사진 → 세션 순서와 맞추고, 여러 사진은 id 순서로 잠근다.
     * 엔티티를 만들지 않는다 — 10,000장을 엔티티로 올리면 영속성 컨텍스트만 무거워진다.
     * 휴지통 사진과 업로드가 끝나지 않은 사진은 빠진다.
     */
    @Query(value = """
        SELECT p.id FROM photos p
        WHERE p.gallery_id = :galleryId AND p.id IN (:photoIds)
          AND p.deleted_at IS NULL AND p.status <> 'PENDING'
        ORDER BY p.id
        FOR UPDATE
    """, nativeQuery = true)
    fun lockShareablePhotoIds(galleryId: Long, photoIds: Collection<Long>): List<Long>

    /**
     * 한 문장으로 담고 새로 담긴 행 수를 돌려준다. `IDENTITY` 키라 JPA `saveAll`은 묶어 보내지 못하고
     * 한 줄씩 INSERT 한다. 이미 담긴 사진은 건너뛴다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
        INSERT INTO collab_session_photos (collab_session_id, gallery_id, photo_id, version, created_at, updated_at)
        SELECT :sessionId, p.gallery_id, p.id, 0, :now, :now
        FROM photos p
        WHERE p.gallery_id = :galleryId AND p.id IN (:photoIds)
        ON CONFLICT (collab_session_id, photo_id) DO NOTHING
    """, nativeQuery = true)
    fun insertMemberships(sessionId: Long, galleryId: Long, photoIds: Collection<Long>, now: ZonedDateTime): Int

    /** 범위 ALL. 걸러내기와 잠금은 [lockShareablePhotoIds]가 한다. */
    @Query(value = "SELECT p.id FROM photos p WHERE p.gallery_id = :galleryId AND p.deleted_at IS NULL", nativeQuery = true)
    fun findGalleryPhotoIds(galleryId: Long): List<Long>

    /** 범위 CONCEPT_FOLDERS. 살아 있는 세부 폴더에 배정된 사진만, 여러 컨셉에 걸쳐도 한 번만. */
    @Query(value = """
        SELECT DISTINCT a.photo_id FROM detail_folder_assignments a
        JOIN detail_folders d ON d.id = a.detail_folder_id AND d.deleted_at IS NULL
        JOIN concept_folders c ON c.id = d.concept_folder_id AND c.deleted_at IS NULL
        WHERE c.gallery_id = :galleryId AND c.id IN (:conceptFolderIds) AND a.photo_id IS NOT NULL
    """, nativeQuery = true)
    fun findConceptPhotoIds(galleryId: Long, conceptFolderIds: Collection<Long>): List<Long>

    /** 범위 DETAIL_FOLDERS. 숨은(합쳐진·휴지통) 세부 폴더는 호출자가 먼저 걸러 낸다. */
    @Query(value = """
        SELECT DISTINCT a.photo_id FROM detail_folder_assignments a
        JOIN detail_folders d ON d.id = a.detail_folder_id AND d.deleted_at IS NULL
        WHERE d.gallery_id = :galleryId AND d.id IN (:detailFolderIds) AND a.photo_id IS NOT NULL
    """, nativeQuery = true)
    fun findDetailPhotoIds(galleryId: Long, detailFolderIds: Collection<Long>): List<Long>

    /** 호출자가 세션을 잠그고 모든 요청 사진의 스코프를 확인한 뒤에만 제거한다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from CollabSessionPhoto p where p.collabSessionId = :sessionId and p.photoId in :photoIds")
    fun deleteMemberships(sessionId: Long, photoIds: Collection<Long>): Int

    /**
     * 공유폴더가 지금 보여 주는 사진. 담긴 사진 중 휴지통에 없고 업로드가 끝난 것만 — 휴지통 사진은 숨겼다가 복원하면 다시 보인다.
     * 컨셉·세부 폴더 배정은 보지 않는다. 공유폴더는 그 폴더들과 따로 산다.
     */
    @Query(value = """
        SELECT s.id AS sessionId, p.id AS photoId
        FROM collab_sessions s
        JOIN galleries g ON g.id = s.gallery_id AND g.deleted_at IS NULL
        JOIN collab_session_photos m ON m.collab_session_id = s.id AND m.gallery_id = s.gallery_id
        JOIN photos p ON p.id = m.photo_id AND p.deleted_at IS NULL AND p.status <> 'PENDING'
        WHERE s.id IN (:sessionIds) AND s.deleted_at IS NULL
        ORDER BY s.id, p.display_order, p.id
    """, nativeQuery = true)
    fun findSharedPhotos(sessionIds: Collection<Long>): List<CollabSharedPhotoProjection>
}
