package com.soma.wes.trash.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.RecordingTrashPhotoStorageConfig
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.TrashRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/**
 * 보관 기간 만료 purge의 규칙을 확인한다 — 무엇을 걷고, 무엇을 남기고, 실패하면 어떻게 되는가.
 *
 * 시계를 고정하는 대신 실제 보관 정책보다 한 시간 전인 시각을 `deleted_at`에 심는다.
 * 정책 일수가 바뀌어도 만료 경계 테스트가 같은 의미를 유지한다.
 */
@IntegrationTest
@Import(RecordingTrashPhotoStorageConfig::class)
class TrashEraserTest @Autowired constructor(
    private val trashEraser: TrashEraser,
    private val trashRepository: TrashRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val retouchFixture: RetouchFixture,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val photoStorage: RecordingTrashPhotoStorage,
    private val trashProperties: TrashProperties,
    private val workspaceRepository: WorkspaceRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun resetStorage() {
        // 데이터는 DatabaseCleaner가 걷어가지만, 가짜 스토리지의 호출 기록은 DB 밖이다.
        photoStorage.reset()
    }

    @Test
    fun `보관 기간이 지난 것만 걷는다`() {
        // given
        val expiredGalleryId = createGallery()
        savePhoto(expiredGalleryId)
        trashGallery(expiredGalleryId, expiredAt())

        val freshGalleryId = createGallery()
        val freshPhoto = savePhoto(freshGalleryId)
        freshPhoto.moveToTrash(ZonedDateTime.now().minusDays(1))
        photoRepository.saveAndFlush(freshPhoto)

        // when
        trashEraser.purgeExpired()

        // then
        assertThat(countGalleryRows(expiredGalleryId)).isEqualTo(0L)
        // 하루밖에 안 된 사진은 남는다 — 아직 복원할 수 있어야 한다.
        assertThat(trashRepository.findTrashedPhotos(freshGalleryId).map { it.photoId })
            .isEqualTo(listOf(freshPhoto.requiredId))
    }

    @Test
    fun `갤러리 purge는 휴지통에 있던 사진의 원본까지 걷는다`() {
        // given
        val galleryId = createGallery()
        val hidden = savePhoto(galleryId)
        hidden.moveToTrash(expiredAt())
        photoRepository.saveAndFlush(hidden)
        val alive = savePhoto(galleryId)
        val historicalKeys = insertPhotoHistory(alive.requiredId, "gallery-purge")
        trashGallery(galleryId, expiredAt())

        // when
        trashEraser.purgeExpired()

        // then
        val deleted = photoStorage.deletedKeys()
        assertSoftly { softly ->
            softly.assertThat(deleted)
                .describedAs("휴지통에 있던 사진의 원본이 지워지지 않았다")
                .contains(hidden.storageKey)
            softly.assertThat(deleted)
                .describedAs("살아 있던 사진의 원본이 지워지지 않았다")
                .contains(alive.storageKey)
            softly.assertThat(deleted)
                .describedAs("사진 리비전·교체 업로드가 지워지지 않았다")
                .containsAll(historicalKeys)
            softly.assertThat(countGalleryRows(galleryId)).isEqualTo(0L)
        }
    }

    @Test
    fun `갤러리 purge는 보정 파일까지 걷는다`() {
        // given
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        val retouched = retouchFixture.주석_추가(
            retouchFixture.결과와_함께_완료된_회차(galleryId, photoIds = listOf(photo.requiredId)),
        )
        val artifactKey = "retouch-artifacts/${retouched.single().requiredId}/pending-result.jpg"
        jdbcTemplate.update(
            """
            INSERT INTO admin_retouch_artifact_uploads
                (round_id, retouch_photo_id, artifact_type, storage_key, original_file_name,
                 content_type, status, expires_at, reason, created_at)
            VALUES (?, ?, 'RESULT', ?, 'pending-result.jpg', 'image/jpeg', 'PENDING',
                    CURRENT_TIMESTAMP + INTERVAL '1 day', 'test purge', CURRENT_TIMESTAMP)
            """.trimIndent(),
            retouched.single().roundId,
            retouched.single().requiredId,
            artifactKey,
        )
        trashGallery(galleryId, expiredAt())

        // when
        trashEraser.purgeExpired()

        // then
        val deleted = photoStorage.deletedKeys()
        assertSoftly { softly ->
            softly.assertThat(deleted)
                .describedAs("보정 결과가 지워지지 않았다")
                .contains(retouched.single().resultKey)
            softly.assertThat(deleted)
                .describedAs("주석 이미지가 지워지지 않았다")
                .contains(retouched.single().annotationKey)
            softly.assertThat(deleted)
                .describedAs("아직 연결되지 않은 관리자 보정 업로드가 지워지지 않았다")
                .contains(artifactKey)
            softly.assertThat(countGalleryRows(galleryId)).isEqualTo(0L)
        }
    }

    @Test
    fun `갤러리 purge는 외부 리비전이 공유하는 원본과 예상 미리보기 key를 보존한다`() {
        // given
        val targetGalleryId = createGallery()
        val targetPhoto = savePhoto(targetGalleryId)
        val unrelatedGalleryId = createGallery()
        val unrelatedPhoto = savePhoto(unrelatedGalleryId)
        val sharedStorageKey = "revisions/shared/across-galleries.jpg"
        listOf(targetPhoto.requiredId, unrelatedPhoto.requiredId).forEach { photoId ->
            jdbcTemplate.update(
                """
                INSERT INTO admin_photo_revisions
                    (photo_id, revision_number, storage_key, original_file_name, content_type, created_at)
                VALUES (?, 1, ?, 'shared.jpg', 'image/jpeg', CURRENT_TIMESTAMP)
                """.trimIndent(),
                photoId,
                sharedStorageKey,
            )
        }
        val targetPreviewCollisionKey = "revisions/shared/preview-collision.png"
        val unrelatedPreviewCollisionKey = "revisions/shared/preview-collision.webp"
        listOf(
            targetPhoto.requiredId to targetPreviewCollisionKey,
            unrelatedPhoto.requiredId to unrelatedPreviewCollisionKey,
        ).forEach { (photoId, storageKey) ->
            jdbcTemplate.update(
                """
                INSERT INTO admin_photo_revisions
                    (photo_id, revision_number, storage_key, original_file_name, content_type, created_at)
                VALUES (?, 2, ?, 'preview-collision', 'image/jpeg', CURRENT_TIMESTAMP)
                """.trimIndent(),
                photoId,
                storageKey,
            )
        }
        trashGallery(targetGalleryId, expiredAt())

        // when
        trashEraser.purgeExpired()

        // then
        assertThat(countGalleryRows(targetGalleryId)).isZero()
        assertThat(countGalleryRows(unrelatedGalleryId)).isOne()
        assertThat(photoStorage.deletedKeys()).doesNotContain(
            sharedStorageKey,
            TrashEraser.expectedPreviewKeyOf(sharedStorageKey),
            unrelatedPreviewCollisionKey,
            TrashEraser.expectedPreviewKeyOf(targetPreviewCollisionKey),
        )
        assertThat(photoStorage.deletedKeys()).contains(targetPreviewCollisionKey)
    }

    @Test
    fun `S3 삭제가 실패하면 행을 남겨 다음 시각에 다시 걷는다`() {
        // given
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        insertOperationalPayloads(photo.requiredId, "target")
        trashGallery(galleryId, expiredAt())
        val unrelatedGalleryId = createGallery()
        val unrelatedPhoto = savePhoto(unrelatedGalleryId)
        insertOperationalPayloads(unrelatedPhoto.requiredId, "unrelated")
        photoStorage.failDelete = true

        // when
        trashEraser.purgeExpired()

        // then
        // 행이 남아 있어야 다음 purge가 같은 대상을 다시 집는다. DB를 먼저 지우면 이 경로가 없다.
        assertThat(countGalleryRows(galleryId)).isEqualTo(1L)
        assertThat(countOperationalRows("admin_idempotency_keys", "target_id", photo.requiredId)).isOne()
        assertThat(countOperationalRows("admin_processing_jobs", "target_id", photo.requiredId)).isOne()
        assertThat(countOperationalRows("admin_notification_outbox", "source_id", photo.requiredId)).isOne()

        // when
        photoStorage.failDelete = false
        trashEraser.purgeExpired()

        // then
        // lease가 살아 있는 동안에는 부분 삭제 가능성을 이유로 restore/admin과 재시도를 막는다.
        assertThat(countGalleryRows(galleryId)).isOne()
        assertThat(countPurgeClaims("GALLERY", galleryId)).isOne()

        // when
        jdbcTemplate.update(
            "UPDATE product_purge_claims SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                "WHERE resource_type = 'GALLERY' AND resource_id = ?",
            galleryId,
        )
        trashEraser.purgeExpired()

        // then
        assertThat(countGalleryRows(galleryId)).isEqualTo(0L)
        assertThat(countPurgeClaims("GALLERY", galleryId)).isZero()
        assertThat(countOperationalRows("admin_idempotency_keys", "target_id", photo.requiredId)).isZero()
        assertThat(countOperationalRows("admin_processing_jobs", "target_id", photo.requiredId)).isZero()
        assertThat(countOperationalRows("admin_notification_outbox", "source_id", photo.requiredId)).isZero()
        assertThat(countOperationalRows("admin_idempotency_keys", "target_id", unrelatedPhoto.requiredId)).isOne()
        assertThat(countOperationalRows("admin_processing_jobs", "target_id", unrelatedPhoto.requiredId)).isOne()
        assertThat(countOperationalRows("admin_notification_outbox", "source_id", unrelatedPhoto.requiredId)).isOne()
    }

    @Test
    fun `관리자 dead letter 배치는 제품 만료 스케줄러가 우회 삭제하지 않는다`() {
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        trashGallery(galleryId, expiredAt())
        lockWithAdminBatch("GALLERY", galleryId, "PURGE_FAILED")

        trashEraser.purgeExpired()

        assertThat(countGalleryRows(galleryId)).isOne()
        assertThat(photoStorage.deletedKeys()).doesNotContain(photo.storageKey)
    }

    @Test
    fun `관리자 배치가 소유한 하위 사진이 있으면 제품 갤러리 purge를 막는다`() {
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        trashGallery(galleryId, expiredAt())
        lockWithAdminBatch("PHOTO", photo.requiredId, "ACTIVE")

        trashEraser.purgeExpired()

        assertThat(countGalleryRows(galleryId)).isOne()
        assertThat(photoStorage.deletedKeys()).doesNotContain(photo.storageKey)
    }

    @Test
    fun `entry가 없는 상위 스튜디오 관리자 배치도 선행 휴지통 갤러리 purge를 막는다`() {
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        trashGallery(galleryId, expiredAt())
        val studioId = checkNotNull(
            jdbcTemplate.queryForObject(
                "SELECT workspace_id FROM galleries WHERE id = ?",
                Long::class.java,
                galleryId,
            ),
        )
        // 이미 product trash인 gallery/photo entry는 admin moveToTrash UPDATE에 잡히지 않는 상황.
        lockWithAdminBatch("STUDIO", studioId, "ACTIVE")

        trashEraser.purgeExpired()

        assertThat(countGalleryRows(galleryId)).isOne()
        assertThat(photoStorage.deletedKeys()).doesNotContain(photo.storageKey)
    }

    @Test
    fun `모든 관리자 child trash parent가 갤러리 cascade로 사라지지 않게 보호된다`() {
        val commentGallery = createGallery()
        val commentParent = insertCollabSession(commentGallery, "child-comment")
        insertChildTrash("COLLAB_COMMENT", 91_001, "COLLABORATION", commentParent)
        trashGallery(commentGallery, expiredAt())

        val likeGallery = createGallery()
        val likeParent = insertCollabSession(likeGallery, "child-like")
        insertChildTrash("COLLAB_LIKE", 91_002, "COLLABORATION", likeParent)
        trashGallery(likeGallery, expiredAt())

        val retouchGallery = createGallery()
        val retouchParent = insertRetouchRound(retouchGallery, "DRAFTING")
        insertChildTrash("RETOUCH_ITEM", 91_004, "RETOUCH_REQUEST", retouchParent)
        trashGallery(retouchGallery, expiredAt())

        trashEraser.purgeExpired()

        assertThat(
            listOf(commentGallery, likeGallery, retouchGallery).map(::countGalleryRows),
        ).containsOnly(1L)
        assertThat(photoStorage.deletedKeys()).isEmpty()
    }

    @Test
    fun `보호된 만료 사진 하나가 무관한 만료 사진 purge를 막지 않는다`() {
        val galleryId = createGallery()
        val protected = savePhoto(galleryId)
        val safe = savePhoto(galleryId)
        val retouchItem = retouchFixture.결과와_함께_완료된_회차(
            galleryId,
            photoIds = listOf(protected.requiredId),
        ).single()
        jdbcTemplate.update(
            "UPDATE retouch_photos SET deleted_at = ? WHERE id = ?",
            expiredAt().toOffsetDateTime(),
            retouchItem.requiredId,
        )
        insertChildTrash("RETOUCH_ITEM", retouchItem.requiredId, "RETOUCH_REQUEST", retouchItem.roundId)
        listOf(protected, safe).forEach { photo ->
            photo.moveToTrash(expiredAt())
            photoRepository.saveAndFlush(photo)
        }

        trashEraser.purgeExpired()

        assertThat(countPhotoRows(protected.requiredId)).isOne()
        assertThat(countPhotoRows(safe.requiredId)).isZero()
        assertThat(photoStorage.deletedKeys()).contains(safe.storageKey).doesNotContain(protected.storageKey)
    }

    @Test
    fun `lease takeover는 이전 worker token의 finalize 소유권을 제거한다`() {
        val galleryId = createGallery()
        trashGallery(galleryId, expiredAt())
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val firstNow = ZonedDateTime.now()
        transactionTemplate.executeWithoutResult {
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            assertThat(trashRepository.lockGalleryPurgeScope(galleryId)).isTrue()
            assertThat(
                trashRepository.tryAcquirePurgeClaim(
                    "GALLERY", galleryId, first, firstNow, firstNow.plusMinutes(15),
                ),
            ).isTrue()
        }
        jdbcTemplate.update(
            "UPDATE product_purge_claims SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                "WHERE resource_type = 'GALLERY' AND resource_id = ?",
            galleryId,
        )
        transactionTemplate.executeWithoutResult {
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            assertThat(trashRepository.lockGalleryPurgeScope(galleryId)).isTrue()
            val takeoverAt = ZonedDateTime.now()
            assertThat(
                trashRepository.tryAcquirePurgeClaim(
                    "GALLERY", galleryId, second, takeoverAt, takeoverAt.plusMinutes(15),
                ),
            ).isTrue()
        }

        val oldWorkerOwns = transactionTemplate.execute {
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            trashRepository.lockGalleryPurgeScope(galleryId)
            trashRepository.ownsPurgeClaimsForUpdate("GALLERY", listOf(galleryId), first)
        }
        val newWorkerOwns = transactionTemplate.execute {
            trashRepository.lockPurgeCoordinationForGallery(galleryId)
            trashRepository.lockGalleryPurgeScope(galleryId)
            trashRepository.ownsPurgeClaimsForUpdate("GALLERY", listOf(galleryId), second)
        }
        assertThat(oldWorkerOwns).isFalse()
        assertThat(newWorkerOwns).isTrue()
        assertThat(countGalleryRows(galleryId)).isOne()
    }

    @Test
    fun `관리자 배치 생성이 먼저 사진을 잠그면 제품 purge는 커밋을 기다린 뒤 양보한다`() {
        val galleryId = createGallery()
        val photo = savePhoto(galleryId)
        trashGallery(galleryId, expiredAt())
        val adminPrepared = CountDownLatch(1)
        val allowAdminCommit = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val adminFuture = executor.submit {
                transactionTemplate.executeWithoutResult {
                    jdbcTemplate.update(
                        "UPDATE photos SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
                        photo.requiredId,
                    )
                    lockWithAdminBatch("PHOTO", photo.requiredId, "ACTIVE")
                    adminPrepared.countDown()
                    check(allowAdminCommit.await(5, TimeUnit.SECONDS)) { "관리자 커밋 대기 시간이 초과됐습니다" }
                }
            }
            check(adminPrepared.await(5, TimeUnit.SECONDS)) { "관리자 배치 준비 시간이 초과됐습니다" }

            val purgeFuture = executor.submit<Boolean> { trashEraser.eraseGallery(galleryId) }
            org.assertj.core.api.Assertions.assertThatThrownBy {
                purgeFuture.get(250, TimeUnit.MILLISECONDS)
            }.isInstanceOf(TimeoutException::class.java)

            allowAdminCommit.countDown()
            adminFuture.get(5, TimeUnit.SECONDS)
            assertThat(purgeFuture.get(5, TimeUnit.SECONDS)).isFalse()
            assertThat(countGalleryRows(galleryId)).isOne()
            assertThat(photoStorage.deletedKeys()).doesNotContain(photo.storageKey)
        } finally {
            allowAdminCommit.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    // --- helpers ---

    private fun createGallery(): Long {
        val workspace = workspaceRepository.save(Workspace.studio("테스트 스튜디오"))
        val studio = studioRepository.save(
            Studio(
                userId = workspace.requiredId,
                name = "테스트 스튜디오",
                galleryUrl = "eraser-${sequence.incrementAndGet()}",
            ),
        )
        return checkNotNull(galleryRepository.save(Gallery(studioId = studio.id, title = "본식")).id)
    }

    private fun savePhoto(galleryId: Long): Photo =
        photoRepository.saveAndFlush(
            Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/photo-${sequence.incrementAndGet()}.jpg",
                originalFileName = "photo.jpg",
                contentType = "image/jpeg",
                displayOrder = 0,
            ),
        )

    private fun trashGallery(galleryId: Long, at: ZonedDateTime) {
        val gallery = galleryRepository.findById(galleryId).orElseThrow()
        gallery.moveToTrash(at)
        galleryRepository.saveAndFlush(gallery)
    }

    private fun countGalleryRows(galleryId: Long): Long =
        checkNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM galleries WHERE id = ?", Long::class.java, galleryId))

    private fun countPhotoRows(photoId: Long): Long =
        checkNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Long::class.java, photoId))

    private fun insertCollabSession(galleryId: Long, suffix: String): Long = checkNotNull(
        jdbcTemplate.queryForObject(
            """
            WITH concept AS (
                INSERT INTO concept_folders
                    (gallery_id, name, sort_order, created_source, version, created_at, updated_at)
                VALUES (?, ?, 0, 'USER', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
            )
            INSERT INTO collab_sessions
                (gallery_id, concept_folder_id, name, collab_token, version, created_at, updated_at)
            SELECT ?, concept.id, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM concept
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId,
            "concept-$suffix",
            galleryId,
            suffix,
            "token-$suffix-${sequence.incrementAndGet()}",
        ),
    )

    private fun insertRetouchRound(galleryId: Long, status: String): Long = checkNotNull(
        jdbcTemplate.queryForObject(
            """
            INSERT INTO retouch_rounds
                (gallery_id, round_no, status, version, created_at, updated_at)
            VALUES (?, 1, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId,
            status,
        ),
    )

    private fun insertChildTrash(resourceType: String, resourceId: Long, parentType: String, parentId: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO admin_child_trash_records (
                resource_type, resource_id, parent_type, parent_id, reason, status,
                deleted_at, restore_until, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, 'TEST_PROTECTION', 'ACTIVE', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
            resourceType,
            resourceId,
            parentType,
            parentId,
        )
    }

    private fun countPurgeClaims(resourceType: String, resourceId: Long): Long = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM product_purge_claims WHERE resource_type = ? AND resource_id = ?",
            Long::class.java,
            resourceType,
            resourceId,
        ),
    )

    private fun insertOperationalPayloads(photoId: Long, suffix: String) {
        jdbcTemplate.update(
            """
            INSERT INTO admin_idempotency_keys
                (action, idempotency_key, request_hash, status, result_payload,
                 target_type, target_id, created_at, updated_at)
            VALUES ('REPROCESS_PHOTO', ?, ?, 'COMPLETED', ?, 'PHOTO', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
            "trash-$suffix-$photoId",
            "hash-$suffix-$photoId",
            "{\"photoId\":$photoId}",
            photoId.toString(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_processing_jobs
                (job_type, status, target_type, target_id, payload, attempt_count,
                 reason, created_at, updated_at)
            VALUES ('DERIVATIVE', 'SUCCEEDED', 'PHOTO', ?, CAST(? AS JSONB), 1,
                    'test purge', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
            photoId,
            "{\"photoId\":$photoId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_notification_outbox
                (notification_type, recipient_reference, source_type, source_id, payload,
                 status, attempt_count, reason, created_at, updated_at)
            VALUES ('PHOTO_READY', ?, 'PHOTO', ?, CAST(? AS JSONB),
                    'SENT', 1, 'test purge', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
            "recipient-$suffix",
            photoId,
            "{\"photoId\":$photoId}",
        )
    }

    private fun insertPhotoHistory(photoId: Long, suffix: String): Set<String> {
        val revisionStorageKey = "revisions/$suffix/original.jpg"
        val revisionPreviewKey = "revisions/$suffix/preview.jpg"
        val replacementStorageKey = "replacements/$suffix/pending.jpg"
        jdbcTemplate.update(
            """
            INSERT INTO admin_photo_revisions
                (photo_id, revision_number, storage_key, preview_key,
                 original_file_name, content_type, created_at)
            VALUES (?, 1, ?, ?, 'original.jpg', 'image/jpeg', CURRENT_TIMESTAMP)
            """.trimIndent(),
            photoId,
            revisionStorageKey,
            revisionPreviewKey,
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_photo_replacement_uploads
                (photo_id, storage_key, original_file_name, content_type, status,
                 expires_at, reason, created_at)
            VALUES (?, ?, 'pending.jpg', 'image/jpeg', 'PENDING',
                    CURRENT_TIMESTAMP + INTERVAL '1 day', 'test purge', CURRENT_TIMESTAMP)
            """.trimIndent(),
            photoId,
            replacementStorageKey,
        )
        return setOf(
            revisionStorageKey,
            revisionPreviewKey,
            TrashEraser.expectedPreviewKeyOf(revisionStorageKey),
            replacementStorageKey,
            TrashEraser.expectedPreviewKeyOf(replacementStorageKey),
        )
    }

    private fun countOperationalRows(table: String, idColumn: String, id: Long): Long = checkNotNull(
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM $table WHERE $idColumn::TEXT = ?",
            Long::class.java,
            id.toString(),
        ),
    )

    private fun lockWithAdminBatch(resourceType: String, resourceId: Long, status: String) {
        val batchId = checkNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO admin_trash_batches
                    (root_type, root_id, root_label, reason, status, deleted_at, restore_until,
                     created_at, updated_at)
                VALUES (?, ?, ?, 'reasonCategory=TEST_OPERATION operatorReasonProvided=true', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                resourceType,
                resourceId,
                "$resourceType #$resourceId",
                status,
            ),
        )
        jdbcTemplate.update(
            """
            INSERT INTO admin_trash_entries
                (batch_id, resource_type, resource_id, is_root, relation_path, created_at)
            VALUES (?, ?, ?, TRUE, 'ROOT', CURRENT_TIMESTAMP)
            """.trimIndent(),
            batchId,
            resourceType,
            resourceId,
        )
    }

    private fun expiredAt(): ZonedDateTime =
        ZonedDateTime.now().minus(trashProperties.retention).minusHours(1)

}
