package com.soma.wes.photo.service

import com.soma.wes.gallery.domain.GalleryStatus
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
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.fixture.StudioFixture
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
                        IssueUploadUrlsRequest.FileRequest(fileName = "DSC_0001.JPG", contentType = "image/jpeg"),
                        IssueUploadUrlsRequest.FileRequest(fileName = "DSC_0002.HEIC", contentType = "image/heic"),
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
                            IssueUploadUrlsRequest.FileRequest(fileName = "raw.arw", contentType = "image/x-sony-arw"),
                        ),
                    ),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.UNSUPPORTED_CONTENT_TYPE)
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
                            IssueUploadUrlsRequest.FileRequest(fileName = "a.jpg", contentType = "image/jpeg"),
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
        fun `완료 통보가 분석 행을 함께 만든다`() {
            // 행의 존재는 이 서버 소유, 컬럼 값은 Lambda 소유 — 사진은 빈 분석 행과 함께 태어난다.
            // given
            val photoIds = issueUploadUrls(count = 2)
            assertThat(photoAnalysisRepository.findAllByPhotoIdIn(photoIds)).isEmpty()

            // when
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )

            // then
            val rows = photoAnalysisRepository.findAllByPhotoIdIn(photoIds)
            assertThat(rows).hasSize(2)
            assertThat(rows).allSatisfy { row ->
                assertThat(row.embedding).isNull()
                assertThat(row.isAnalyzed).isFalse()
            }
        }

        @Test
        fun `이미 있는 분석 행은 건드리지 않는다`() {
            // 재통보(markUploaded처럼 멱등)가 임베딩이 적힌 행을 빈 행으로 되돌리면 안 된다.
            // given — 분석 행에 벡터가 먼저 적재된 상태
            val photoIds = issueUploadUrls(count = 1)
            photoFixture.벡터_적재(
                photoIds.first(),
                FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f },
            )

            // when
            photoService.completeUpload(
                fixture.galleryId, fixture.photographer.id!!, CompleteUploadRequest(photoIds),
            )

            // then
            val row = photoAnalysisRepository.findAllByPhotoIdIn(photoIds).single()
            assertThat(row.embedding).isNotNull()
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
            // 통째로 사라지면 안 된다 -- requirePhotographerOrCouple이었다면 여기서 막혔다.
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

    /** 발급 자체가 파이프라인의 1단계라 픽스처가 아니라 서비스로 만든다. */
    private fun issueUploadUrls(count: Int): List<Long> {
        val files = (1..count).map {
            IssueUploadUrlsRequest.FileRequest(fileName = "photo-$it.jpg", contentType = "image/jpeg")
        }

        return photoService.issueUploadUrls(
            fixture.galleryId, fixture.photographer.id!!, IssueUploadUrlsRequest(files),
        ).uploads.map { it.photoId }
    }
}
