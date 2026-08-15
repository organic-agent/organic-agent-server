package com.soma.wes.trash.service

import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.service.CollabGuestQueryService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.TestSequence
import com.soma.wes.trash.RecordingTrashPhotoStorage
import com.soma.wes.trash.dto.request.EraseTrashedPhotosRequest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.exception.TrashErrorCode
import com.soma.wes.trash.exception.TrashException
import com.soma.wes.trash.repository.TrashRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * 휴지통의 세 동작 — 목록·복원·즉시 물리 삭제 — 과 소프트 삭제의 가시성 규칙을 서비스 경계에서
 * 확인한다. 휴지통으로 *보내는* 것은 소유 도메인의 일이라 [PhotoService]·[GalleryService]를
 * 함께 주입해 시나리오를 만든다.
 *
 * [com.soma.wes.photo.service.PhotoStorage]는 기록형 가짜로 바꾼다. presign은 로컬 서명
 * 연산이지만 deleteAll은 진짜 S3 API 호출이라 테스트에서 실행할 수 없고, 무엇보다
 * "무슨 키를 지웠는지"가 검증 대상이다.
 */
@IntegrationTest
@Import(TrashServiceTest.RecordingStorageConfig::class)
class TrashServiceTest @Autowired constructor(
    private val trashService: TrashService,
    private val photoService: PhotoService,
    private val galleryService: GalleryService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
    private val trashRepository: TrashRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val photoStorage: RecordingTrashPhotoStorage,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun resetStorage() {
        // 데이터는 DatabaseCleaner가 걷어가지만, 가짜 스토리지의 호출 기록은 DB 밖이다.
        photoStorage.reset()
    }

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("사진 휴지통")
    inner class PhotoTrash {

        @Test
        fun `사진을 휴지통으로 보내면 목록에서 사라지고 휴지통 목록에 나타난다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val trashedId = photoIds.first()

            // when
            val result = photoService.moveToTrash(
                fixture.galleryId, fixture.photographer.id!!, DeletePhotosRequest(listOf(trashedId)),
            )

            // then
            assertThat(result.count).isEqualTo(1)
            assertThat(listPhotos().contents).hasSize(2)
            assertThatThrownBy { photoService.get(fixture.galleryId, trashedId, fixture.photographer.id!!) }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)

            val trash = trashService.listPhotos(fixture.galleryId, fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(trash.photos).hasSize(1)
                softly.assertThat(trash.photos[0].photoId).isEqualTo(trashedId)
                softly.assertThat(trash.photos[0].expiresAt).isNotNull()
                softly.assertThat(trash.photos[0].viewUrl).contains("storage.test/view")
            }
        }

        @Test
        fun `휴지통 사진을 복원하면 목록에 돌아온다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            moveToTrash(photoIds)

            // when
            trashService.restorePhotos(fixture.galleryId, fixture.photographer.id!!, RestorePhotosRequest(photoIds))

            // then
            assertThat(listPhotos().contents).hasSize(2)
            assertThat(trashRepository.findTrashedPhotos(fixture.galleryId)).isEmpty()
        }

        @Test
        fun `휴지통에 없는 사진이 섞이면 복원 전체가 거절된다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val trashedId = photoIds.first()
            moveToTrash(listOf(trashedId))

            // when & then
            assertThatThrownBy {
                trashService.restorePhotos(
                    fixture.galleryId, fixture.photographer.id!!, RestorePhotosRequest(photoIds),
                )
            }
                .isInstanceOf(TrashException::class.java)
                .extracting("errorCode")
                .isEqualTo(TrashErrorCode.PHOTO_NOT_IN_TRASH)

            // 전부-아니면-거부 — 섞인 요청은 휴지통에 있던 쪽도 되살리지 않는다.
            assertThat(trashRepository.findTrashedPhotos(fixture.galleryId).map { it.photoId })
                .isEqualTo(listOf(trashedId))
        }

        @Test
        fun `부부는 사진을 지우지도 되살리지도 못한다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                photoService.moveToTrash(fixture.galleryId, fixture.member.id!!, DeletePhotosRequest(photoIds))
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            assertThatThrownBy {
                trashService.restorePhotos(fixture.galleryId, fixture.member.id!!, RestorePhotosRequest(photoIds))
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `업로드 URL이 살아 있으면 즉시 삭제가 거절된다`() {
            // 발급 직후의 URL(30분)이 아직 유효하다. DB만 지우면 그 URL로 뒤늦게 올라온 객체가
            // 아무 행도 가리키지 않는 채 남는다.
            // given
            val photoIds = uploadPhotos(count = 1)
            moveToTrash(photoIds)

            // when & then
            assertThatThrownBy {
                trashService.erasePhotos(
                    fixture.galleryId, fixture.photographer.id!!, EraseTrashedPhotosRequest(photoIds),
                )
            }
                .isInstanceOf(TrashException::class.java)
                .extracting("errorCode")
                .isEqualTo(TrashErrorCode.UPLOAD_URL_ACTIVE)

            assertThat(photoStorage.deletedKeys()).isEmpty()
        }

        @Test
        fun `사진 즉시 삭제는 원본과 파생 미리보기 키를 지우고 행을 걷는다`() {
            // given
            val photoIds = uploadPhotos(count = 1)
            val storageKey = photoRepository.findAllById(photoIds).single().storageKey
            expireUploadUrls(photoIds)
            moveToTrash(photoIds)

            // when
            trashService.erasePhotos(fixture.galleryId, fixture.photographer.id!!, EraseTrashedPhotosRequest(photoIds))

            // then
            // preview_key는 아직 null이라(임베딩 전) 원본과 파생 규칙 위치, 두 키다.
            assertThat(photoStorage.deletedKeys())
                .isEqualTo(setOf(storageKey, "previews/${storageKey.substringBeforeLast('.')}.jpg"))
            assertThat(countPhotoRows(photoIds.single())).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("갤러리 휴지통")
    inner class GalleryTrash {

        @Test
        fun `갤러리를 휴지통으로 보내면 안이 통째로 닫힌다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val collabToken = openCollabSession()
            assertThat(collabGuestQueryService.getLanding(collabToken).galleryTitle).isEqualTo("본식")

            // when
            galleryService.moveToTrash(fixture.galleryId, fixture.photographer.id!!)

            // then
            // 갤러리 하나가 숨는 것으로 상세·사진 목록·하객 링크가 전부 404다.
            assertThatThrownBy { galleryService.get(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
            assertThatThrownBy { listPhotos() }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
            assertThatThrownBy { collabGuestQueryService.getLanding(collabToken) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)

            val trashed = trashService.listGalleries(fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(trashed).hasSize(1)
                softly.assertThat(trashed[0].galleryId).isEqualTo(fixture.galleryId)
                softly.assertThat(trashed[0].photoCount).isEqualTo(2L)
                softly.assertThat(trashed[0].expiresAt).isNotNull()
            }
        }

        @Test
        fun `갤러리 복원은 지우기 전 모습 그대로 되살린다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val trashedPhotoId = photoIds.first()
            moveToTrash(listOf(trashedPhotoId))
            galleryService.moveToTrash(fixture.galleryId, fixture.photographer.id!!)

            // when
            trashService.restoreGallery(fixture.galleryId, fixture.photographer.id!!)

            // then
            // 갤러리보다 먼저 개별 삭제된 사진은 복원 뒤에도 휴지통에 남는다.
            assertThat(listPhotos().contents).hasSize(1)
            assertThat(trashRepository.findTrashedPhotos(fixture.galleryId).map { it.photoId })
                .isEqualTo(listOf(trashedPhotoId))
        }

        @Test
        fun `갤러리 즉시 삭제는 휴지통에 있던 사진의 원본까지 걷는다`() {
            // given
            val photoIds = uploadPhotos(count = 2)
            val storageKeys = photoRepository.findAllById(photoIds).map { it.storageKey }
            expireUploadUrls(photoIds)
            moveToTrash(listOf(photoIds.first()))
            galleryService.moveToTrash(fixture.galleryId, fixture.photographer.id!!)

            // when
            trashService.eraseGallery(fixture.galleryId, fixture.photographer.id!!)

            // then
            val deleted = photoStorage.deletedKeys()
            assertSoftly { softly ->
                storageKeys.forEach { key ->
                    softly.assertThat(deleted).describedAs("$key 원본이 지워지지 않았다").contains(key)
                }
                softly.assertThat(
                    jdbcTemplate.queryForObject("SELECT count(*) FROM galleries WHERE id = ?", Long::class.java, fixture.galleryId),
                ).isEqualTo(0L)
                photoIds.forEach { softly.assertThat(countPhotoRows(it)).isEqualTo(0) }
            }
        }

        @Test
        fun `남의 갤러리는 내 휴지통에서 보이지도 되살려지지도 않는다`() {
            // given
            val others = galleryFixture.멤버와_열린_갤러리()
            galleryService.moveToTrash(others.galleryId, others.photographer.id!!)

            // when & then
            assertThat(trashService.listGalleries(fixture.photographer.id!!)).isEmpty()
            assertThatThrownBy { trashService.restoreGallery(others.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(TrashException::class.java)
                .extracting("errorCode")
                .isEqualTo(TrashErrorCode.GALLERY_NOT_IN_TRASH)
        }
    }

    // --- helpers ---

    /**
     * 픽스처 대신 서비스 경로로 발급·완료까지 마친 사진. storage key와 업로드 URL 수명이
     * 실제 규칙대로 생기므로, 지워진 키와 URL 가드가 검증 대상인 시나리오는 이쪽을 쓴다.
     */
    private fun uploadPhotos(count: Int): List<Long> {
        val files = (1..count).map {
            IssueUploadUrlsRequest.FileRequest(fileName = "photo-${TestSequence.next()}.jpg", contentType = "image/jpeg")
        }
        val issued = photoService.issueUploadUrls(
            fixture.galleryId, fixture.photographer.id!!, IssueUploadUrlsRequest(files),
        )

        val photoIds = issued.uploads.map { it.photoId }
        photoService.completeUpload(fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds))
        return photoIds
    }

    private fun moveToTrash(photoIds: List<Long>) {
        photoService.moveToTrash(fixture.galleryId, fixture.photographer.id!!, DeletePhotosRequest(photoIds))
    }

    /** 발급 시각 기준 30분짜리 URL을 이미 지난 것으로 만든다. 즉시 삭제의 409 가드를 지나기 위해서다. */
    private fun expireUploadUrls(photoIds: List<Long>) {
        val photos = photoRepository.findAllById(photoIds)
        photos.forEach { it.recordUploadUrlExpiration(Instant.now().minusSeconds(60)) }
        photoRepository.saveAllAndFlush(photos)
    }

    /** 하객 협업 링크의 토큰. 세션은 부부가 여는 것이라 부부 멤버로 연다. */
    private fun openCollabSession(): String {
        val session = collabSessionService.open(
            fixture.galleryId, fixture.member.id!!, OpenCollabSessionRequest(name = "본식 후보"),
        )
        return session.collabUrl.substringAfterLast('/')
    }

    private fun listPhotos() =
        photoService.list(fixture.galleryId, fixture.photographer.id!!, status = null, minScore = null, page = 0, size = 10)

    private fun countPhotoRows(photoId: Long): Int =
        checkNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Int::class.java, photoId))

    @TestConfiguration(proxyBeanMethods = false)
    class RecordingStorageConfig {

        @Bean
        @Primary
        fun recordingTrashPhotoStorage(): RecordingTrashPhotoStorage = RecordingTrashPhotoStorage()
    }
}
