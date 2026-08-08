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
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun `실패한 실행의 claim을 해제하면 같은 요청으로 다시 준비할 수 있다`() {
        val studio = studioRepository.save(
            Studio(userId = 10L, name = "오가닉", galleryUrl = "organic-studio"),
        )
        val studioId = checkNotNull(studio.id)
        val requestId = UUID.randomUUID()
        val reason = "문의 WES-CS-6 최종 확인"
        val first = assertIs<StudioDeletionPreparation.Pending>(
            processor.prepare(studioId, 99L, requestId, "organic-studio", reason),
        )

        processor.release(first.plan)
        val retried = processor.prepare(studioId, 99L, requestId, "organic-studio", reason)

        assertIs<StudioDeletionPreparation.Pending>(retried)
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
