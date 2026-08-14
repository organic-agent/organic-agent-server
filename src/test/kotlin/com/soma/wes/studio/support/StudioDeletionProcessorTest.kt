package com.soma.wes.studio.support

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.domain.PhotoSelectionItem
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.domain.StudioDeletionClaimState
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class, StudioDeletionProcessor::class)
class StudioDeletionProcessorTest @Autowired constructor(
    private val processor: StudioDeletionProcessor,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val photoRatingRepository: PhotoRatingRepository,
    private val auditRepository: StudioDeletionAuditRepository,
    private val claimRepository: StudioDeletionClaimRepository,
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `스튜디오 삭제가 모든 하위 DB 데이터에 cascade되고 감사 기록은 남는다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.save(Gallery(studioId = studioId, title = "본식"))
        val galleryId = checkNotNull(gallery.id)
        galleryInviteRepository.save(
            GalleryInvite(
                galleryId = galleryId,
                token = "deletion-test-token",
                expiresAt = ZonedDateTime.parse("2026-09-01T00:00:00+09:00[Asia/Seoul]"),
            ),
        )
        galleryMemberRepository.save(GalleryMember(galleryId = galleryId, userId = 20L))
        val photo = photoRepository.save(
            Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/original.heic",
                originalFileName = "original.heic",
                contentType = "image/heic",
            ),
        )
        photo.previewKey = "previews/galleries/$galleryId/original.jpg"
        photoRepository.flush()
        val photoId = photo.requiredId

        val folder = photoFolderRepository.save(PhotoFolder.of(galleryId, "대표 사진"))
        photoFolderItemRepository.save(PhotoFolderItem(folder.requiredId, photoId))
        val selection = photoSelectionRepository.save(PhotoSelection(galleryId))
        photoSelectionItemRepository.save(PhotoSelectionItem(selection.requiredId, photoId))
        photoRatingRepository.save(PhotoRating.of(photoId, score = 5, ratedBy = 20L))

        val requestId = UUID.fromString("1ff31a7d-2ff2-4b97-89b6-c40521fd51eb")
        val preparation = processor.prepare(
            studioId = studioId,
            operatorUserId = 99L,
            requestId = requestId,
            confirmedGalleryUrl = "organic-studio",
            reason = "문의 WES-CS-1 최종 확인",
        )
        val plan = assertIs<StudioDeletionPreparation.Pending>(preparation).plan

        val result = processor.delete(plan, 99L, "문의 WES-CS-1 최종 확인")

        assertEquals(1, result.galleryCount)
        assertEquals(1, result.photoCount)
        assertEquals(2, result.objectCount)
        assertFalse(studioRepository.existsById(studioId))
        assertEquals(0, galleryRepository.count())
        assertEquals(0, galleryInviteRepository.count())
        assertEquals(0, galleryMemberRepository.count())
        assertEquals(0, photoRepository.count())
        assertEquals(0, photoFolderRepository.count())
        assertEquals(0, photoFolderItemRepository.count())
        assertEquals(0, photoSelectionRepository.count())
        assertEquals(0, photoSelectionItemRepository.count())
        assertEquals(0, photoRatingRepository.count())
        assertEquals(result.auditId, auditRepository.findByRequestId(requestId)?.id)
        assertEquals(0, claimRepository.count())

        val repeated = processor.prepare(
            studioId = studioId,
            operatorUserId = 99L,
            requestId = requestId,
            confirmedGalleryUrl = "organic-studio",
            reason = "문의 WES-CS-1 최종 확인",
        )
        assertEquals(result, assertIs<StudioDeletionPreparation.Completed>(repeated).result)
    }

    @Test
    fun `같은 요청 ID가 실행 중이면 S3 삭제 준비를 하나만 허용한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-5 최종 확인"

        val first = processor.prepare(studioId, 99L, requestId, "organic-studio", reason)
        assertIs<StudioDeletionPreparation.Pending>(first)

        val exception = assertFailsWith<StudioException> {
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason)
        }

        assertEquals(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS, exception.errorCode)
        assertEquals(1, claimRepository.count())
    }

    @Test
    fun `서로 다른 요청 ID도 같은 스튜디오에서는 하나만 실행한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val reason = "문의 WES-CS-5 최종 확인"

        processor.prepare(studioId, 99L, UUID.randomUUID(), "organic-studio", reason)
        val exception = assertFailsWith<StudioException> {
            processor.prepare(studioId, 99L, UUID.randomUUID(), "organic-studio", reason)
        }

        assertEquals(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS, exception.errorCode)
        assertEquals(1, claimRepository.count())
    }

    @Test
    fun `아직 유효한 PUT URL이 있으면 claim과 S3 삭제 준비를 남기지 않는다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.save(Gallery(studioId = studioId, title = "본식"))
        val photo = Photo(
            galleryId = checkNotNull(gallery.id),
            storageKey = "galleries/${gallery.id}/pending.jpg",
            originalFileName = "pending.jpg",
            contentType = "image/jpeg",
        )
        photo.recordUploadUrlExpiration(Instant.now().plusSeconds(300))
        photoRepository.saveAndFlush(photo)

        val exception = assertFailsWith<StudioException> {
            processor.prepare(
                studioId,
                99L,
                UUID.randomUUID(),
                "organic-studio",
                "문의 WES-CS-30 최종 확인",
            )
        }

        assertEquals(StudioErrorCode.DELETION_UPLOAD_URL_ACTIVE, exception.errorCode)
        assertTrue(studioRepository.existsById(studioId))
    }

    @Test
    fun `만료 컬럼을 모르는 기존 writer도 DB default가 30분 동안 삭제를 막는다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.saveAndFlush(Gallery(studioId = studioId, title = "본식"))
        val galleryId = checkNotNull(gallery.id)
        val insertedAt = Instant.now()
        val photoId = jdbcTemplate.queryForObject(
            """
                INSERT INTO photos (
                    gallery_id, storage_key, original_file_name, display_order, status, content_type
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
            """.trimIndent(),
            Long::class.java,
            galleryId,
            "galleries/$galleryId/legacy.jpg",
            "legacy.jpg",
            0,
            "PENDING",
            "image/jpeg",
        )
        val expiresAt = assertNotNull(
            jdbcTemplate.queryForObject(
                "select upload_url_expires_at from photos where id = ?",
                Instant::class.java,
                photoId,
            ),
        )
        assertTrue(expiresAt.isAfter(insertedAt.plusSeconds(29 * 60)))
        assertTrue(expiresAt.isBefore(Instant.now().plusSeconds(31 * 60)))

        val exception = assertFailsWith<StudioException> {
            processor.prepare(
                studioId,
                99L,
                UUID.randomUUID(),
                "organic-studio",
                "문의 WES-CS-30 최종 확인",
            )
        }

        assertEquals(StudioErrorCode.DELETION_UPLOAD_URL_ACTIVE, exception.errorCode)
    }

    @Test
    fun `PUT URL이 만료됐으면 같은 사진을 삭제 스냅샷에 포함한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val gallery = galleryRepository.save(Gallery(studioId = studioId, title = "본식"))
        val photo = Photo(
            galleryId = checkNotNull(gallery.id),
            storageKey = "galleries/${gallery.id}/expired.jpg",
            originalFileName = "expired.jpg",
            contentType = "image/jpeg",
        )
        photo.recordUploadUrlExpiration(Instant.now().minusSeconds(1))
        photoRepository.saveAndFlush(photo)

        val preparation = processor.prepare(
            studioId,
            99L,
            UUID.randomUUID(),
            "organic-studio",
            "문의 WES-CS-31 최종 확인",
        )

        val plan = assertIs<StudioDeletionPreparation.Pending>(preparation).plan
        assertEquals(setOf(photo.requiredId), plan.photos.mapTo(mutableSetOf()) { it.photoId })
    }

    @Test
    fun `Embedding job의 shared fence가 있으면 삭제 claim을 만들지 않는다`() {
        val studio = studioRepository.saveAndFlush(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val fenceKey = StudioWriteFence.key(studioId)

        dataSource.connection.use { embeddingConnection ->
            embeddingConnection.prepareStatement("select pg_advisory_lock_shared(?)").use { statement ->
                statement.setLong(1, fenceKey)
                statement.execute()
            }
            try {
                val exception = assertFailsWith<StudioException> {
                    processor.prepare(
                        studioId,
                        99L,
                        UUID.randomUUID(),
                        "organic-studio",
                        "문의 WES-CS-32 최종 확인",
                    )
                }

                assertEquals(StudioErrorCode.DELETION_WRITER_IN_PROGRESS, exception.errorCode)
                assertEquals(0, claimRepository.count())
            } finally {
                embeddingConnection.prepareStatement("select pg_advisory_unlock_shared(?)").use { statement ->
                    statement.setLong(1, fenceKey)
                    statement.execute()
                }
            }
        }
    }

    @Test
    fun `S3 시작 후 실패한 claim은 writer를 막은 채 같은 요청이 새 token으로 재개한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-6 최종 확인"
        val first = assertIs<StudioDeletionPreparation.Pending>(
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason),
        )

        processor.markRetryable(first.plan)
        assertEquals(StudioDeletionClaimState.RETRYABLE, claimRepository.findById(requestId).orElseThrow().state)

        val staleDelete = assertFailsWith<StudioException> {
            processor.delete(first.plan, 99L, reason)
        }
        assertEquals(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS, staleDelete.errorCode)

        val retried = assertIs<StudioDeletionPreparation.Pending>(
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason),
        )

        assertNotEquals(first.plan.claimToken, retried.plan.claimToken)
        assertEquals(StudioDeletionProcessor.CURRENT_SUPPORTED_PLAN_VERSION, retried.plan.planVersion)
        val staleMark = assertFailsWith<StudioException> {
            processor.markRetryable(first.plan)
        }
        assertEquals(StudioErrorCode.DELETION_REQUEST_IN_PROGRESS, staleMark.errorCode)
        val activeClaim = claimRepository.findById(requestId).orElseThrow()
        assertEquals(StudioDeletionClaimState.RUNNING, activeClaim.state)
        assertEquals(retried.plan.claimToken, activeClaim.claimToken)
        assertEquals(1, claimRepository.count())
    }

    @Test
    fun `현재 artifact가 지원하지 않는 retry plan은 claim을 유지한 채 거절한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-61 최종 확인"
        val first = assertIs<StudioDeletionPreparation.Pending>(
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason),
        )
        processor.markRetryable(first.plan)
        jdbcTemplate.update(
            "update studio_deletion_claims set plan_version = ? where request_id = ?",
            StudioDeletionProcessor.CURRENT_SUPPORTED_PLAN_VERSION + 1,
            requestId,
        )

        val exception = assertFailsWith<StudioException> {
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason)
        }

        assertEquals(StudioErrorCode.DELETION_PLAN_VERSION_UNSUPPORTED, exception.errorCode)
        assertEquals(1, claimRepository.count())
    }

    @Test
    fun `실행 중인 요청 ID를 다른 내용으로 재사용하면 충돌로 거절한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()

        processor.prepare(studioId, 99L, requestId, "organic-studio", "문의 WES-CS-7 최종 확인")
        val exception = assertFailsWith<StudioException> {
            processor.prepare(studioId, 99L, requestId, "organic-studio", "다른 문의 내용")
        }

        assertEquals(StudioErrorCode.DELETION_REQUEST_CONFLICT, exception.errorCode)
    }

    @Test
    fun `확인한 galleryUrl이 현재 대상과 다르면 삭제 준비를 거절한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)

        val exception = assertFailsWith<StudioException> {
            processor.prepare(
                studioId = studioId,
                operatorUserId = 99L,
                requestId = UUID.randomUUID(),
                confirmedGalleryUrl = "another-studio",
                reason = "문의 WES-CS-2 최종 확인",
            )
        }

        assertEquals(StudioErrorCode.DELETION_TARGET_MISMATCH, exception.errorCode)
        assertTrue(studioRepository.existsById(studioId))
    }

    @Test
    fun `같은 요청 ID를 다른 내용으로 재사용하면 충돌로 거절한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-3 최종 확인"
        val preparation = processor.prepare(
            studioId = studioId,
            operatorUserId = 99L,
            requestId = requestId,
            confirmedGalleryUrl = "organic-studio",
            reason = reason,
        )
        processor.delete(assertIs<StudioDeletionPreparation.Pending>(preparation).plan, 99L, reason)

        val exception = assertFailsWith<StudioException> {
            processor.prepare(
                studioId = studioId,
                operatorUserId = 99L,
                requestId = requestId,
                confirmedGalleryUrl = "organic-studio",
                reason = "다른 문의 내용",
            )
        }

        assertEquals(StudioErrorCode.DELETION_REQUEST_CONFLICT, exception.errorCode)
    }

    @Test
    fun `S3 삭제 준비 뒤 하위 데이터가 바뀌면 DB 삭제를 중단한다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-4 최종 확인"
        val preparation = processor.prepare(
            studioId = studioId,
            operatorUserId = 99L,
            requestId = requestId,
            confirmedGalleryUrl = "organic-studio",
            reason = reason,
        )
        val plan = assertIs<StudioDeletionPreparation.Pending>(preparation).plan
        galleryRepository.saveAndFlush(Gallery(studioId = studioId, title = "삭제 준비 뒤 추가된 갤러리"))

        val exception = assertFailsWith<StudioException> {
            processor.delete(plan, 99L, reason)
        }

        assertEquals(StudioErrorCode.DELETION_TARGET_CHANGED, exception.errorCode)
        assertTrue(studioRepository.existsById(studioId))
        assertEquals(1, galleryRepository.count())
        assertEquals(0, auditRepository.count())
    }
}
