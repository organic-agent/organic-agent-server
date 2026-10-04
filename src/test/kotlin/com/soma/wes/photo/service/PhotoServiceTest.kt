package com.soma.wes.photo.service

import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.service.PersonalGalleryService
import com.soma.wes.photo.dto.request.DeletePhotosRequest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.service.TrashService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.page.PageRequests
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.request.ReissueUploadUrlsRequest
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.support.CapturedLogs
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.util.UriComponentsBuilder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZonedDateTime
import java.time.Instant
import java.time.LocalDateTime

/**
 * 원본 사진 파이프라인 — 업로드 URL 발급 → 완료 통보 → 목록·집계 → 상세 — 을 서비스 경계에서
 * 확인한다.
 *
 * S3에 실제로 올리는 단계는 여기 없다. 그 단계는 프론트가 서명 URL로 직접 하고 서버는 관여하지
 * 않으므로, 서버 쪽에서 검증할 수 있는 것은 "서명이 붙은 URL을 제대로 내주는가"까지다.
 * 임베딩 실행은 analysis 도메인(AnalysisService)의 몫이라 그쪽 테스트가 맡는다.
 */
@IntegrationTest
class PhotoServiceTest @Autowired constructor(
    private val photoService: PhotoService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val galleryRepository: GalleryRepository,
    private val personalGalleryService: PersonalGalleryService,
    private val billing: BillingFixture,
    private val trashService: TrashService,
    private val clock: Clock,
    private val jdbcTemplate: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("업로드 URL을 발급할 때")
    inner class IssueUploadUrls {

        @Test
        fun `요청한 파일 수만큼 서명된 업로드 URL을 발급한다`() {
            // when
            val result = photoService.issueUploadUrls(
                fixture.galleryId,
                fixture.photographer.id!!,
                IssueUploadUrlsRequest(
                    files = listOf(
                        IssueUploadUrlsRequest.FileRequest(fileName = "DSC_0001.JPG", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw=="),
                        IssueUploadUrlsRequest.FileRequest(fileName = "DSC_0002.HEIC", contentType = "image/heic", contentLength = 1024, crc32c = "wdRDgw=="),
                    ),
                ),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.uploads).hasSize(2)
                // 프론트가 이 URL로 S3에 직접 PUT 한다. 서명이 없으면 비공개 버킷이 거절한다.
                softly.assertThat(result.uploads[0].uploadUrl).contains("X-Amz-Signature")
                softly.assertThat(result.uploads[0].storageKey).contains("galleries/${fixture.galleryId}/")
                // 원본 파일명은 컬럼에만 남는다. 키에 그대로 쓰면 중복·인코딩 문제가 생긴다.
                softly.assertThat(result.uploads[0].storageKey).doesNotContain("DSC_0001")
                softly.assertThat(result.uploadUrlTtlSeconds).isEqualTo(1800L)
            }

            val expirations = photoRepository.findAll().map { photo ->
                checkNotNull(photo.uploadUrlExpiresAt)
            }
            assertThat(expirations.all { it.isAfter(Instant.now()) }).isTrue()
        }

        @Test
        fun `서명은 Content-Length 와 x-amz-checksum-crc32c 만 요구하고 SDK 내부 헤더는 요구하지 않는다`() {
            // 브라우저가 맞출 수 있는 헤더만 서명에 있어야 한다. x-amz-sdk-checksum-algorithm 이 서명에 끼면 어떤 클라이언트도
            // 올릴 수 없고, 체크섬 헤더가 서명에서 빠지면 서명 안 된 x-amz-* 헤더로 S3가 거절한다.
            // when
            val result = photoService.issueUploadUrls(
                fixture.galleryId,
                fixture.photographer.id!!,
                IssueUploadUrlsRequest(
                    files = listOf(
                        IssueUploadUrlsRequest.FileRequest(fileName = "a.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw=="),
                    ),
                ),
            )

            // then
            val signedHeaders = signedHeadersOf(checkNotNull(result.uploads.single().uploadUrl))
            assertThat(signedHeaders)
                .contains("content-length", "content-type", "x-amz-checksum-crc32c")
                .doesNotContain("x-amz-sdk-checksum-algorithm")
        }

        @Test
        fun `체크섬 형식이 틀리면 발급 단계에서 막는다`() {
            // 값이 서명에 그대로 들어가므로, 여기서 거르지 않으면 S3가 PUT을 거절할 때까지 드러나지 않는다.
            // when & then
            assertThatThrownBy {
                photoService.issueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    IssueUploadUrlsRequest(
                        files = listOf(
                            IssueUploadUrlsRequest.FileRequest(fileName = "a.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "c1d44383"),
                        ),
                    ),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.INVALID_CHECKSUM)
        }

        @Test
        fun `임베딩이 읽을 수 없는 형식은 발급 단계에서 막는다`() {
            // 여기서 막지 않으면 업로드는 전부 성공하고 임베딩만 조용히 실패해,
            // 사진이 영영 UPLOADED에 머무는 것으로만 드러난다.
            // when & then
            assertThatThrownBy {
                photoService.issueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    IssueUploadUrlsRequest(
                        files = listOf(
                            IssueUploadUrlsRequest.FileRequest(fileName = "raw.arw", contentType = "image/x-sony-arw", contentLength = 1024, crc32c = "wdRDgw=="),
                        ),
                    ),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }

        @Test
        fun `크기 상한을 넘는 파일은 발급 단계에서 막는다`() {
            // 크기는 서명에 들어가므로 서버가 볼 수 있는 유일한 지점이 발급이다.
            // when & then
            assertThatThrownBy {
                photoService.issueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    IssueUploadUrlsRequest(
                        listOf(
                            IssueUploadUrlsRequest.FileRequest(
                                fileName = "huge.jpg",
                                contentType = "image/jpeg",
                                contentLength = 21L * 1024 * 1024,
                                crc32c = "wdRDgw==",
                            ),
                        ),
                    ),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.INVALID_CONTENT_LENGTH)
        }

        @Test
        fun `남의 갤러리에는 업로드 URL을 발급받을 수 없다`() {
            // given
            val stranger = studioFixture.작가()

            // when & then
            assertThatThrownBy {
                photoService.issueUploadUrls(
                    fixture.galleryId,
                    stranger.id!!,
                    IssueUploadUrlsRequest(
                        files = listOf(
                            IssueUploadUrlsRequest.FileRequest(fileName = "a.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw=="),
                        ),
                    ),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("업로드 URL을 재발급할 때")
    inner class ReissueUploadUrls {

        @Test
        fun `아직 올라오지 않은 사진에 새 URL을 주고 행은 새로 만들지 않는다`() {
            // given
            val photoIds = issueUploadUrls(count = 2)

            // when
            val result = photoService.reissueUploadUrls(
                fixture.galleryId,
                fixture.photographer.id!!,
                ReissueUploadUrlsRequest(photoIds.map { ReissueUploadUrlsRequest.PhotoRequest(photoId = it, contentLength = 2048, crc32c = "wdRDgw==") }),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.uploads.map { it.photoId }).containsExactlyInAnyOrderElementsOf(photoIds)
                softly.assertThat(result.uploads).allSatisfy { assertThat(it.uploadUrl).contains("X-Amz-Signature") }
                softly.assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(2L)
            }
        }

        @Test
        fun `이미 올라온 사진이 섞여 있으면 전부 거절한다`() {
            // 새 URL로 원본이 덮이는 일을 막는다.
            // given
            val photoIds = issueUploadUrls(count = 2)
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(listOf(photoIds.first())),
            )

            // when & then
            assertThatThrownBy {
                photoService.reissueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    ReissueUploadUrlsRequest(photoIds.map { ReissueUploadUrlsRequest.PhotoRequest(photoId = it, contentLength = 2048, crc32c = "wdRDgw==") }),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_ALREADY_UPLOADED)
        }
    }

    @Nested
    @DisplayName("업로드 완료를 통보할 때")
    inner class CompleteUpload {

        @Test
        fun `완료 통보를 받아야 임베딩 대상이 된다`() {
            // given
            val photoIds = issueUploadUrls(count = 3)

            val before = photoService.summarize(fixture.galleryId, fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(before.total).isEqualTo(3L)
                softly.assertThat(before.pending).isEqualTo(3L)
                softly.assertThat(before.uploaded).isEqualTo(0L)
            }

            // when
            val result = photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )

            // then
            assertThat(result.count).isEqualTo(3)
            val after = photoService.summarize(fixture.galleryId, fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(after.pending).isEqualTo(0L)
                softly.assertThat(after.uploaded).isEqualTo(3L)
                softly.assertThat(after.embedded).isEqualTo(0L)
            }
        }

        @Test
        fun `완료 통보는 분석 행을 만들지 않는다`() {
            // 분석 행은 임베더가 첫 배치에서 UPSERT 로 만든다 — 진행 집계는 없는 행을 "아직"으로 읽는다.
            // given
            val photoIds = issueUploadUrls(count = 2)

            // when
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )

            // then
            assertThat(photoAnalysisRepository.findAllByPhotoIdIn(photoIds)).isEmpty()
        }

        @Test
        fun `재통보는 멱등이고 벡터가 있는 사진의 집계는 임베딩으로 센다`() {
            // given — 벡터가 먼저 적재된 사진
            val photoIds = issueUploadUrls(count = 1)
            photoFixture.벡터_적재(
                photoIds.first(),
                FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f },
            )

            // when
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )

            // then
            val summary = photoService.summarize(fixture.galleryId, fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(summary.uploaded).isEqualTo(1L)
                softly.assertThat(summary.embedded).isEqualTo(1L)
                softly.assertThat(summary.scored).isEqualTo(0L)
                softly.assertThat(summary.failed).isEqualTo(0L)
            }
        }

        @Test
        fun `다른 갤러리의 사진 id를 섞어 통보하면 실패한다`() {
            // 갤러리 권한만 확인하고 id를 그대로 믿으면, 자기 갤러리 하나로 남의 사진 상태를 바꿀 수 있다.
            // given
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoIds = photoFixture.대기중_사진(otherFixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                photoService.completeUpload(
                    fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(otherPhotoIds),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("업로드 로그를 남길 때")
    inner class UploadLogging {

        @Test
        fun `발급과 재발급과 완료 통보가 한 줄씩 남는다`() {
            // when
            val events = CapturedLogs(PhotoService::class).use { logs ->
                val photoIds = issueUploadUrls(count = 2)
                photoService.reissueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    ReissueUploadUrlsRequest(
                        photos = listOf(ReissueUploadUrlsRequest.PhotoRequest(photoId = photoIds.first(), contentLength = 1024, crc32c = "wdRDgw==")),
                    ),
                )
                photoService.completeUpload(fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds))
                listOf("upload.issue", "upload.reissue", "upload.complete")
                    .map { event -> logs.eventsOf(event).single().formattedMessage }
            }

            // then
            val who = "gallery=${fixture.galleryId} user=${fixture.photographer.id}"
            assertThat(events).containsExactly(
                "event=upload.issue $who photos=2 bytes=2048",
                "event=upload.reissue $who photos=1",
                "event=upload.complete $who photos=2",
            )
        }

        @Test
        fun `검증에서 거절된 발급은 코드와 장수를 남기고 발급 줄은 남기지 않는다`() {
            // when
            val (rejected, issued) = CapturedLogs(PhotoService::class).use { logs ->
                assertThatThrownBy {
                    photoService.issueUploadUrls(
                        fixture.galleryId,
                        fixture.photographer.id!!,
                        IssueUploadUrlsRequest(
                            files = listOf(
                                IssueUploadUrlsRequest.FileRequest(fileName = "a.gif", contentType = "image/gif", contentLength = 1024, crc32c = "wdRDgw=="),
                            ),
                        ),
                    )
                }.isInstanceOf(PhotoException::class.java)
                logs.eventsOf("upload.rejected") to logs.eventsOf("upload.issue")
            }

            // then — "사진이 안 올라간다"는 문의에서 이유를 찾는 줄이다
            assertSoftly { softly ->
                softly.assertThat(rejected.single().formattedMessage).isEqualTo(
                    "event=upload.rejected gallery=${fixture.galleryId} code=${PhotoErrorCode.UNSUPPORTED_CONTENT_TYPE.code} photos=1",
                )
                softly.assertThat(issued).isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("사진 목록을 조회할 때")
    inner class ListPhotos {

        @Test
        fun `올라온 사진에만 조회 URL이 붙는다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when
            val result = list(fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.contents).hasSize(2)
                softly.assertThat(result.totalCount).isEqualTo(2L)
                softly.assertThat(result.hasNext).isFalse()
                // 버킷이 비공개라 이 URL이 브라우저가 이미지를 받을 유일한 통로다.
                softly.assertThat(result.contents[0].status).isEqualTo(PhotoStatus.UPLOADED)
                softly.assertThat(result.contents[0].viewUrl).contains("X-Amz-Signature")
                // 아직 S3에 객체가 없는 사진에 URL을 주면 <img>가 깨진 이미지를 그린다.
                softly.assertThat(result.contents[1].status).isEqualTo(PhotoStatus.PENDING)
                softly.assertThat(result.contents[1].viewUrl).isNull()
            }
        }

        @Test
        fun `파생본이 생기기 전에는 원본을 주고 준비되지 않았음을 알린다`() {
            // 임베딩 실행 전까지는 파생본이 없다. 원본이 HEIC라면 이 구간에서 미리보기가
            // 비어 보이는데, previewReady가 false라는 사실만으로 프론트가 그 사정을 안내할 수 있다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            val storageKey = photoRepository.findById(photoId).orElseThrow().storageKey

            // when
            val result = list(fixture.photographer.id!!)

            // then
            assertThat(result.contents[0].previewReady).isFalse()
            assertThat(result.contents[0].viewUrl).contains(storageKey)
        }

        @Test
        fun `파생본이 있으면 조회 URL이 원본이 아니라 파생본을 가리킨다`() {
            // 아이폰 원본(HEIC)은 Chrome·Firefox·Edge가 디코딩하지 못한다. 임베딩 Lambda가
            // 만들어 둔 JPEG 파생본을 서명해 줘야 <img src>에 그대로 넣을 수 있다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            val photo = photoRepository.findById(photoId).orElseThrow()
            val previewKey = "previews/${photo.storageKey.substringBeforeLast('.')}.jpg"
            photo.previewKey = previewKey
            photoRepository.saveAndFlush(photo)

            // when
            val result = list(fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.contents[0].previewReady).isTrue()
                softly.assertThat(result.contents[0].viewUrl).contains(previewKey)
                softly.assertThat(result.contents[0].viewUrl).contains("X-Amz-Signature")
                // storageKey는 그대로 원본을 가리킨다. 파생본은 화면용일 뿐 원본을 대신하지 않는다.
                softly.assertThat(result.contents[0].storageKey).isEqualTo(photo.storageKey)
            }
        }

        @Test
        fun `상태로 걸러 받을 수 있다`() {
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when
            val result = list(fixture.photographer.id!!, status = PhotoStatus.UPLOADED)

            // then
            assertThat(result.contents).hasSize(2)
            assertThat(result.totalCount).isEqualTo(2L)
        }

        @Test
        fun `페이지 크기 상한을 넘기면 거절하지 않고 깎는다`() {
            // 페이지 파라미터가 이상한 것은 화면의 버그이지 사용자가 고칠 수 있는 잘못이 아니다.
            // 거절하면 목록이 통째로 비지만, 깎으면 첫 페이지라도 보인다.
            // when
            val result = list(fixture.photographer.id!!, size = 1001)

            // then
            assertThat(result.size).isEqualTo(PageRequests.MAX_SIZE)
        }

        @Test
        fun `음수 페이지는 첫 페이지로 깎는다`() {
            // when
            val result = list(fixture.photographer.id!!, page = -1)

            // then
            assertThat(result.page).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("목록 접근 권한을 확인할 때")
    inner class ListAccess {

        @Test
        fun `초대된 부부도 전체 사진 목록을 본다`() {
            // 전체를 훑고 마음에 드는 것을 고르는 것이 부부가 하는 일이다. 그 전체가 열리지 않으면
            // 부부는 비슷한 사진 묶음(클러스터·폴더)으로만 사진을 만나게 된다.
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            val result = list(fixture.member.id!!)

            // then
            assertThat(result.contents).hasSize(2)
        }

        @Test
        fun `아직 열지 않은 갤러리의 목록은 부부에게 보이지 않는다`() {
            // 작가가 사진을 올리고 정리하는 동안은 부부에게 이 갤러리가 없는 것과 같다.
            // given
            // 픽스처는 열린 갤러리를 만든다. 필요한 것은 아직 열리지 않은 상태라 필드를 직접 되돌린다.
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.status = GalleryStatus.DRAFT
            galleryRepository.saveAndFlush(gallery)

            // when & then
            assertThatThrownBy { list(fixture.member.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `마감이 지나도 부부는 목록을 볼 수 있다`() {
            // 목록은 고르는 동작이 아니라 보는 동작이다. 마감됐다고 자기 갤러리의 사진이
            // 통째로 사라지면 안 된다 -- requireManagerOrSelectionEditor이었다면 여기서 막혔다.
            // given
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            galleryFixture.마감_지남(fixture.galleryId)

            // when
            val result = list(fixture.member.id!!)

            // then
            assertThat(result.contents).hasSize(1)
        }

        @Test
        fun `갤러리와 무관한 사용자는 목록을 볼 수 없다`() {
            // given
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy { list(stranger.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("상세의 URL을 서명할 때")
    inner class SignDetailUrls {

        @Test
        fun `원본 URL과 파생본 URL이 각각 다른 키를 가리킨다`() {
            // 상세의 핵심이다. 목록용 URL은 파생 JPEG(줄어든 이미지)를 가리키므로 확대하면
            // 뭉개지고, 원본은 원래 크기지만 HEIC면 브라우저가 그리지 못한다. 둘 다 줘야 화면이 고른다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            val storageKey = attachPreview(photoId)

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.previewReady).isTrue()
                softly.assertThat(result.viewUrl).contains("previews/$storageKey")
                // 원본은 파생본으로 갈아타지 않는다.
                softly.assertThat(result.originalUrl).contains(storageKey)
                softly.assertThat(result.originalUrl).doesNotContain("previews/")
                softly.assertThat(result.originalUrl).contains("X-Amz-Signature")
            }
        }

        @Test
        fun `원본 URL은 목록용보다 오래 산다`() {
            // 상세는 한 장을 오래 열어두는 화면이다. 목록과 같은 수명으로 서명하면 확대해 보는
            // 도중에 만료되고, 그때 S3는 403을 돌려주므로 사용자에게는 사진이 깨진 것처럼 보인다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.photographer.id!!)

            // then
            assertThat(result.viewUrlTtlSeconds).isEqualTo(900L)
            assertThat(result.originalUrlTtlSeconds).isEqualTo(3600L)
        }

        @Test
        fun `파생본이 아직 없는 사진도 상세가 열린다`() {
            // 파생본은 임베딩 Lambda가 만든다. 그전까지 화면이 아무것도 못 여는 상태가 되면
            // 업로드 직후의 갤러리에서는 상세가 통째로 쓸모없어진다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.photographer.id!!)

            // then
            // 파생본이 없으면 viewUrl과 originalUrl 둘 다 원본을 가리킨다. 수명만 다르다.
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(PhotoStatus.UPLOADED)
                softly.assertThat(result.previewReady).isFalse()
                // 촬영 정보도 임베딩 Lambda가 채운다. 아직 없으면 통째로 null이다 --
                // 빈 값만 가득한 객체를 주면 화면이 빈 칸을 늘어놓게 된다.
                softly.assertThat(result.metadata).isNull()
                softly.assertThat(result.viewUrl)
                    .describedAs("파생본이 없으면 viewUrl도 원본을 가리켜야 한다")
                    .contains(result.storageKey)
                softly.assertThat(result.originalUrl)
                    .describedAs("originalUrl은 언제나 원본을 가리킨다")
                    .contains(result.storageKey)
            }
        }

        @Test
        fun `아직 올라오지 않은 사진은 URL을 주지 않는다`() {
            // PENDING은 서명 URL만 발급됐을 뿐 S3에 객체가 없을 수 있다. URL을 주면
            // 프론트의 <img>가 깨진 이미지를 그린다.
            // given
            val photoId = photoFixture.대기중_사진(fixture.galleryId, count = 1).first()

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(PhotoStatus.PENDING)
                softly.assertThat(result.viewUrl).isNull()
                softly.assertThat(result.originalUrl).isNull()
            }
        }
    }

    @Nested
    @DisplayName("촬영 정보를 보여줄 때")
    inner class ShowMetadata {

        @Test
        fun `촬영 정보가 채워지면 상세에 함께 온다`() {
            // 실제로는 임베딩 Lambda가 벡터와 같은 UPDATE로 채운다. 여기서는 그 결과 상태를 만든다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            val photo = photoRepository.findById(photoId).orElseThrow()
            photo.applyMetadata(
                PhotoMetadata(
                    takenAt = LocalDateTime.of(2026, 5, 16, 14, 32, 10),
                    cameraMake = "Apple",
                    cameraModel = "iPhone 15 Pro",
                    exposureTime = "1/200",
                    fNumber = 2.8,
                    iso = 400,
                    width = 3024,
                    height = 4032,
                    byteSize = 2_411_984,
                ),
            )
            photoRepository.saveAndFlush(photo)

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.photographer.id!!)

            // then
            val metadata = checkNotNull(result.metadata)
            assertSoftly { softly ->
                // 타임존이 붙지 않는다. EXIF에 오프셋이 없어 벽시계 그대로 담기 때문이다 --
                // 서버 타임존으로 해석해 넣으면 여행지에서 찍은 사진이 조용히 옮겨간다.
                softly.assertThat(metadata.takenAt).isEqualTo(LocalDateTime.of(2026, 5, 16, 14, 32, 10))
                softly.assertThat(metadata.cameraModel).isEqualTo("iPhone 15 Pro")
                softly.assertThat(metadata.exposureTime).isEqualTo("1/200")
                softly.assertThat(metadata.fNumber).isEqualTo(2.8)
                softly.assertThat(metadata.iso).isEqualTo(400)
                softly.assertThat(metadata.width).isEqualTo(3024)
                softly.assertThat(metadata.byteSize).isEqualTo(2_411_984L)
            }
        }
    }

    @Nested
    @DisplayName("상세 접근 권한을 확인할 때")
    inner class DetailAccess {

        @Test
        fun `초대받은 부부도 상세는 볼 수 있다`() {
            // 클러스터·폴더에서 고른 한 장을 크게 보는 것은 부부가 하는 일이다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when
            val result = photoService.get(fixture.galleryId, photoId, fixture.member.id!!)

            // then
            assertThat(result.photoId).isEqualTo(photoId)
        }

        @Test
        fun `초대받지 않은 사람은 상세를 볼 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy { photoService.get(fixture.galleryId, photoId, stranger.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `다른 갤러리의 사진 id로는 상세를 열 수 없다`() {
            // 인가는 galleryId로 확인한다. 사진을 id만으로 찾으면 자기 갤러리 하나로
            // 남의 사진과 그 서명 URL까지 받아낼 수 있다.
            // given
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoId = photoFixture.업로드된_사진(otherFixture.galleryId, count = 1).first()

            // when & then
            assertThatThrownBy { photoService.get(fixture.galleryId, otherPhotoId, fixture.photographer.id!!) }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
        }
    }


    @Nested
    @DisplayName("요금제 사진 한도를 적용할 때")
    inner class PlanQuota {
        @Test
        fun `이용 기간이 만료되면 사진 업로드를 발급하지 않는다`() {
            // given
            val owner = userFixture.사용자()
            val gallery = personalGalleryService.create(owner.requiredId, CreatePersonalGalleryRequest(title = "만료할 무료"))
            val stored = galleryRepository.findById(gallery.id).orElseThrow()
            stored.planExpiresAt = ZonedDateTime.now(clock).minusSeconds(1)
            galleryRepository.saveAndFlush(stored)

            // when & then
            assertThatThrownBy { photoService.issueUploadUrls(gallery.id, owner.requiredId, singlePhotoRequest()) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
            assertThat(photoRepository.countByGalleryId(gallery.id)).isZero()
        }

        @Test
        fun `무료 500장까지 발급하고 삭제하면 한도를 돌려준다`() {
            // given
            val owner = userFixture.사용자()
            val gallery = personalGalleryService.create(owner.requiredId, CreatePersonalGalleryRequest(title = "무료 한도"))
            photoFixture.업로드된_사진(gallery.id, count = 499)
            val request = singlePhotoRequest()
            val last = photoService.issueUploadUrls(gallery.id, owner.requiredId, request).uploads.single().photoId

            // when & then
            assertThatThrownBy { photoService.issueUploadUrls(gallery.id, owner.requiredId, request) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
            photoService.moveToTrash(gallery.id, owner.requiredId, DeletePhotosRequest(listOf(last)))
            assertThat(photoService.issueUploadUrls(gallery.id, owner.requiredId, request).uploads).hasSize(1)
            assertThat(photoRepository.countByGalleryId(gallery.id)).isEqualTo(500L)
        }

        @Test
        fun `프로 10000장 경계에서도 삭제 후 다시 업로드할 수 있다`() {
            // given
            val owner = userFixture.사용자()
            val coupon = billing.미사용_프로_쿠폰(owner.requiredId)
            val gallery = personalGalleryService.create(owner.requiredId, CreatePersonalGalleryRequest(
                title = "프로 한도", planId = "pro", couponId = coupon.requiredId,
            ))
            photoFixture.대량_업로드된_사진(gallery.id, count = 9999)
            val request = singlePhotoRequest()
            val last = photoService.issueUploadUrls(gallery.id, owner.requiredId, request).uploads.single().photoId

            // when & then
            assertThatThrownBy { photoService.issueUploadUrls(gallery.id, owner.requiredId, request) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
            photoService.moveToTrash(gallery.id, owner.requiredId, DeletePhotosRequest(listOf(last)))
            assertThat(photoService.issueUploadUrls(gallery.id, owner.requiredId, request).uploads).hasSize(1)
            assertThat(photoRepository.countByGalleryId(gallery.id)).isEqualTo(10_000L)
        }

        @Test
        fun `삭제한 자리에 새 사진을 올렸으면 기존 사진을 복원해 한도를 넘을 수 없다`() {
            // given
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.planMaxPhotoCount = 1
            galleryRepository.saveAndFlush(gallery)
            val deleted = issueUploadUrls(1).single()
            photoService.moveToTrash(fixture.galleryId, fixture.photographer.requiredId, DeletePhotosRequest(listOf(deleted)))
            val replacement = issueUploadUrls(1).single()

            // when & then
            assertThatThrownBy { trashService.restorePhotos(fixture.galleryId, fixture.photographer.requiredId, RestorePhotosRequest(listOf(deleted))) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
            photoService.moveToTrash(fixture.galleryId, fixture.photographer.requiredId, DeletePhotosRequest(listOf(replacement)))
            trashService.restorePhotos(fixture.galleryId, fixture.photographer.requiredId, RestorePhotosRequest(listOf(deleted)))
            assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(1L)
        }

        @Test
        fun `남은 한 장을 동시에 업로드해도 하나만 발급된다`() {
            // given
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.planMaxPhotoCount = 1
            galleryRepository.saveAndFlush(gallery)
            val start = CountDownLatch(1)

            // when
            val results = Executors.newFixedThreadPool(2).use { executor ->
                val futures = (1..2).map { executor.submit<Result<Long>> {
                    start.await()
                    runCatching { photoService.issueUploadUrls(fixture.galleryId, fixture.photographer.requiredId, singlePhotoRequest()).uploads.single().photoId }
                } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            }

            // then
            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            assertThat(checkNotNull(results.single { it.isFailure }.exceptionOrNull()))
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
            assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("올라온 시각을 남길 때")
    inner class UploadedAt {

        @Test
        fun `완료 통보가 올라온 시각을 남기고 재통보는 처음 시각을 그대로 둔다`() {
            // given
            val photoId = issueUploadUrls(count = 1).single()
            assertThat(photoRepository.findById(photoId).orElseThrow().uploadedAt).isNull()

            // when
            photoService.completeUpload(fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(listOf(photoId)))
            val first = photoRepository.findById(photoId).orElseThrow().uploadedAt
            photoService.completeUpload(fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(listOf(photoId)))
            val second = photoRepository.findById(photoId).orElseThrow().uploadedAt

            // then
            assertSoftly { softly ->
                softly.assertThat(first).isNotNull()
                softly.assertThat(second?.toInstant()).isEqualTo(first?.toInstant())
            }
        }
    }

    @Nested
    @DisplayName("URL 이 만료된 사진을 한도에서 뺄 때")
    inner class ExpiredPendingQuota {

        @Test
        fun `URL 이 만료된 PENDING 은 한도에 세지 않아 그만큼 다시 올릴 수 있다`() {
            // given — 한도 2장: 올라온 1장 + 올리다 실패해 URL 이 죽은 1장
            limitPhotosTo(2)
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            expireUploadUrl(issueUploadUrls(count = 1).single())

            // when — 다른 사진을 올린다
            val result = issueUploadUrls(count = 1)

            // then
            assertThat(result).hasSize(1)
        }

        @Test
        fun `URL 이 살아 있는 PENDING 은 한도에 센다`() {
            // given
            limitPhotosTo(2)
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            issueUploadUrls(count = 1)

            // when & then
            assertThatThrownBy { issueUploadUrls(count = 1) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
        }

        @Test
        fun `한도가 찬 뒤에는 만료된 사진을 재발급으로 되살릴 수 없다`() {
            // given — 만료된 사진의 자리를 다른 사진이 채웠다
            limitPhotosTo(2)
            photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val expired = issueUploadUrls(count = 1).single()
            expireUploadUrl(expired)
            issueUploadUrls(count = 1)

            // when & then
            assertThatThrownBy {
                photoService.reissueUploadUrls(
                    fixture.galleryId,
                    fixture.photographer.id!!,
                    ReissueUploadUrlsRequest(listOf(ReissueUploadUrlsRequest.PhotoRequest(photoId = expired, contentLength = 1024, crc32c = "wdRDgw=="))),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.PHOTO_PLAN_LIMIT_EXCEEDED)
        }

        private fun limitPhotosTo(count: Int) {
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.planMaxPhotoCount = count
            galleryRepository.saveAndFlush(gallery)
        }

        private fun expireUploadUrl(photoId: Long) {
            jdbcTemplate.update("UPDATE photos SET upload_url_expires_at = now() - interval '1 minute' WHERE id = ?", photoId)
        }
    }

    private fun singlePhotoRequest(): IssueUploadUrlsRequest = IssueUploadUrlsRequest(listOf(
        IssueUploadUrlsRequest.FileRequest(fileName = "quota.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw=="),
    ))

    // --- helpers ---

    private fun list(
        userId: Long,
        status: PhotoStatus? = null,
        minScore: Int? = null,
        page: Int = 0,
        size: Int = 20,
    ): PhotoPageResponse = photoService.list(fixture.galleryId, userId, status, minScore, page, size)

    /**
     * 임베딩 Lambda가 파생본을 올린 상태를 만들고 원본 키를 돌려준다.
     *
     * 파생본 키 규칙(`previews/{원본 키}`)은 Lambda의 `images.preview_key_for`가 정한다.
     */
    private fun attachPreview(photoId: Long): String {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.previewKey = "previews/${photo.storageKey}"
        photoRepository.saveAndFlush(photo)
        return photo.storageKey
    }

    /** presigned URL 의 `X-Amz-SignedHeaders` — 브라우저가 PUT 때 똑같이 보내야 하는 헤더 목록. */
    private fun signedHeadersOf(uploadUrl: String): List<String> =
        UriComponentsBuilder.fromUriString(uploadUrl).build().queryParams
            .getFirst("X-Amz-SignedHeaders")
            .let { checkNotNull(it) { "서명 헤더 목록이 없다: $uploadUrl" } }
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .split(";")

    /** 발급 자체가 파이프라인의 1단계라 픽스처가 아니라 서비스로 만든다. */
    private fun issueUploadUrls(count: Int): List<Long> {
        val files = (1..count).map {
            IssueUploadUrlsRequest.FileRequest(fileName = "photo-$it.jpg", contentType = "image/jpeg", contentLength = 1024, crc32c = "wdRDgw==")
        }

        return photoService.issueUploadUrls(
            fixture.galleryId, fixture.photographer.id!!, IssueUploadUrlsRequest(files),
        ).uploads.map { it.photoId }
    }
}
