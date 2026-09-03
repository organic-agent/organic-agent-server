package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.TestSequence
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

/**
 * Mock 갤러리 생성의 DB 단계들을 시더 경계에서 확인한다.
 *
 * 보는 것은 넷이다: 복제 원본이 임베딩까지 끝난 사진으로만 골라지는지, 갤러리 행이 기본
 * 제목/요청 값으로 만들어지는지, 복제 행의 노출 순서가 0부터 다시 매겨지는지, 그리고
 * 실패 보상(discard)이 조용히 도는지. S3 복사를 사이에 둔 전체 흐름은 MockGalleryServiceTest가
 * 본다 — 시더는 프로퍼티를 읽지 않으므로(템플릿 id도 파라미터다) 기본 `@IntegrationTest`로 돈다.
 */
@IntegrationTest
class MockGallerySeederTest @Autowired constructor(
    private val mockGallerySeeder: MockGallerySeeder,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
) {

    @Nested
    @DisplayName("템플릿 사진을 고를 때")
    inner class LoadTemplatePhotos {

        @Test
        fun `임베딩까지 끝난 사진만 노출 순서대로 돌려준다`() {
            // PENDING은 S3 객체가 없을 수 있고 UPLOADED는 벡터가 없다 — 섞여 들어가면
            // 복제된 갤러리가 "즉시 체험"이 되지 않는다.
            // given
            val template = 템플릿_갤러리()
            val second = 임베딩_사진(template.requiredId, displayOrder = 5)
            val first = 임베딩_사진(template.requiredId, displayOrder = 2)
            photoRepository.save(사진(template.requiredId, displayOrder = 0)) // PENDING
            photoRepository.save(사진(template.requiredId, displayOrder = 1).also { it.markUploaded() })

            // when
            val templates = mockGallerySeeder.loadTemplatePhotos(template.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(templates).hasSize(2)
                softly.assertThat(templates.map { it.id }).containsExactly(first.id, second.id)
                softly.assertThat(templates).allSatisfy { photo ->
                    assertThat(photo.status).isEqualTo(PhotoStatus.EMBEDDED)
                }
            }
        }

        @Test
        fun `템플릿 갤러리가 없으면 준비되지 않은 것으로 거절한다`() {
            // when & then
            assertThatThrownBy { mockGallerySeeder.loadTemplatePhotos(999_999L) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }

        @Test
        fun `임베딩까지 끝난 사진이 한 장도 없으면 준비되지 않은 것으로 거절한다`() {
            // given
            val template = 템플릿_갤러리()
            photoRepository.save(사진(template.requiredId, displayOrder = 0)) // PENDING뿐

            // when & then
            assertThatThrownBy { mockGallerySeeder.loadTemplatePhotos(template.requiredId) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.MOCK_GALLERY_NOT_READY)
        }
    }

    @Nested
    @DisplayName("갤러리 행을 만들 때")
    inner class CreateGallery {

        @Test
        fun `요청한 작업공간에 DRAFT 갤러리를 만든다`() {
            // given
            val photographer = studioFixture.작가()

            // when
            val request = requestFor(photographer)
            val gallery = mockGallerySeeder.createGallery(photographer.id!!, request)

            // then
            assertSoftly { softly ->
                softly.assertThat(gallery.title).isEqualTo("체험 갤러리")
                softly.assertThat(gallery.status).isEqualTo(GalleryStatus.DRAFT)
                softly.assertThat(gallery.studioId)
                    .isEqualTo(request.workspaceId)
                softly.assertThat(gallery.selectionDeadline).isNull()
                softly.assertThat(gallery.maxSelectablePhotoCount).isNull()
            }
        }

        @Test
        fun `요청이 있으면 요청 값 그대로 만든다`() {
            // given
            val photographer = studioFixture.작가()
            val deadline = ZonedDateTime.now().plusDays(7)
            val request = CreateGalleryRequest(
                workspaceId = studioFixture.소유_스튜디오(photographer).workspaceId,
                title = "체험 갤러리",
                selectionDeadline = deadline,
                maxSelectablePhotoCount = 30,
            )

            // when
            val gallery = mockGallerySeeder.createGallery(photographer.id!!, request)

            // then
            assertSoftly { softly ->
                softly.assertThat(gallery.title).isEqualTo("체험 갤러리")
                softly.assertThat(gallery.selectionDeadline).isEqualTo(deadline)
                softly.assertThat(gallery.maxSelectablePhotoCount).isEqualTo(30)
            }
        }

        @Test
        fun `소속되지 않은 작업공간에는 만들 수 없다`() {
            // given
            val user = userFixture.사용자()
            val owner = studioFixture.작가()
            val request = requestFor(owner)

            // when & then
            assertThatThrownBy { mockGallerySeeder.createGallery(user.id!!, request) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            assertThat(galleryRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `지난 마감 기한은 거절하고 행을 남기지 않는다`() {
            // 컨트롤러의 @Valid는 서비스 직접 호출에서 돌지 않는다 — 도메인의 2차 방어선이 막는다.
            // given
            val photographer = studioFixture.작가()
            val request = CreateGalleryRequest(
                workspaceId = studioFixture.소유_스튜디오(photographer).workspaceId,
                title = "체험 갤러리",
                selectionDeadline = ZonedDateTime.now().minusDays(1),
            )

            // when & then
            assertThatThrownBy { mockGallerySeeder.createGallery(photographer.id!!, request) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_SELECTION_DEADLINE)

            assertThat(galleryRepository.count()).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("복제 사진을 저장할 때")
    inner class PersistPhotos {

        @Test
        fun `계획 순서 그대로 0부터 노출 순서를 다시 매긴다`() {
            // 템플릿의 번호(구멍 난 3, 7)를 복사하면 운영자가 템플릿에서 지운 사진의 구멍이
            // 새 갤러리에 그대로 전파된다.
            // given
            val template = 템플릿_갤러리()
            val sources = listOf(
                임베딩_사진(template.requiredId, displayOrder = 3),
                임베딩_사진(template.requiredId, displayOrder = 7),
            )
            val photographer = studioFixture.작가()
            val gallery = mockGallerySeeder.createGallery(photographer.id!!, requestFor(photographer))
            val plans = sources.map { source ->
                val storageKey = "galleries/${gallery.requiredId}/copy-${TestSequence.next()}.png"
                MockGalleryCopyPlan(
                    source = source,
                    storageKey = storageKey,
                    previewKey = "previews/${storageKey.substringBeforeLast('.')}.jpg",
                )
            }

            // when
            mockGallerySeeder.persistPhotos(gallery, plans)

            // then
            val copies = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(gallery.requiredId)
            assertThat(copies).hasSize(2)
            copies.forEachIndexed { index, copy ->
                assertSoftly { softly ->
                    softly.assertThat(copy.displayOrder).isEqualTo(index)
                    softly.assertThat(copy.storageKey).isEqualTo(plans[index].storageKey)
                    softly.assertThat(copy.previewKey).isEqualTo(plans[index].previewKey)
                    softly.assertThat(copy.status).isEqualTo(PhotoStatus.EMBEDDED)
                    softly.assertThat(vectorOf(copy)).isEqualTo(vectorOf(sources[index]))
                    // 업로드 URL을 발급한 적 없는 복제 행 — 값이 있으면 휴지통 즉시 삭제가 막힌다.
                    softly.assertThat(copy.uploadUrlExpiresAt).isNull()
                }
            }
        }

        @Test
        fun `previewKey 없는 계획은 복제 행도 미리보기 없이 남긴다`() {
            // viewKey가 원본으로 폴백하는 일반 의미론 그대로 남아야 한다.
            // given
            val template = 템플릿_갤러리()
            val source = 임베딩_사진(template.requiredId, displayOrder = 0, withPreview = false)
            val photographer = studioFixture.작가()
            val gallery = mockGallerySeeder.createGallery(photographer.id!!, requestFor(photographer))
            val plan = MockGalleryCopyPlan(
                source = source,
                storageKey = "galleries/${gallery.requiredId}/copy-${TestSequence.next()}.png",
                previewKey = null,
            )

            // when
            mockGallerySeeder.persistPhotos(gallery, listOf(plan))

            // then
            val copy = photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(gallery.requiredId).single()
            assertThat(copy.previewKey).isNull()
        }
    }

    @Nested
    @DisplayName("실패를 보상할 때")
    inner class Discard {

        @Test
        fun `사진 없이 남은 갤러리 행을 걷어낸다`() {
            // given
            val photographer = studioFixture.작가()
            val gallery = mockGallerySeeder.createGallery(photographer.id!!, requestFor(photographer))

            // when
            mockGallerySeeder.discard(gallery.requiredId)

            // then
            assertThat(galleryRepository.existsById(gallery.requiredId)).isFalse()
        }

        @Test
        fun `이미 없는 갤러리면 조용히 지나간다`() {
            // 보상은 best-effort다 — 보상 경로에서 새 예외가 나면 원래 실패를 가린다.
            // when & then
            assertThatCode { mockGallerySeeder.discard(999_999L) }.doesNotThrowAnyException()
        }
    }

    // --- 템플릿 시드 ---

    /**
     * 템플릿 갤러리. 시더는 템플릿 id를 파라미터로 받으므로(프로퍼티가 아니다) 고정 id
     * INSERT 없이 보통 갤러리를 저장하면 된다.
     */
    private fun 템플릿_갤러리(): Gallery {
        val operator = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(operator)
        return galleryRepository.save(
            Gallery(
                studioId = studio.workspaceId,
                createdByUserId = operator.requiredId,
                title = "샘플 템플릿",
            ),
        )
    }

    private fun requestFor(photographer: com.soma.wes.user.domain.User) = CreateGalleryRequest(
        workspaceId = studioFixture.소유_스튜디오(photographer).workspaceId,
        title = "체험 갤러리",
    )

    private fun 임베딩_사진(galleryId: Long, displayOrder: Int, withPreview: Boolean = true): Photo {
        val photo = photoRepository.save(
            사진(galleryId, displayOrder).also { photo ->
                if (withPreview) {
                    photo.previewKey = "previews/${photo.storageKey.substringBeforeLast('.')}.jpg"
                }
                photo.markEmbedded()
            },
        )
        photoAnalysisRepository.save(
            PhotoAnalysis.embeddedBy(
                photoId = photo.requiredId,
                vector = FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION) { (displayOrder + 1) * 0.01f },
                model = "facebook/dinov3-vitb16-pretrain-lvd1689m",
            ),
        )
        return photo
    }

    private fun vectorOf(photo: Photo): FloatArray? =
        photoAnalysisRepository.findById(photo.requiredId).orElseThrow().embedding

    private fun 사진(galleryId: Long, displayOrder: Int): Photo =
        Photo(
            galleryId = galleryId,
            storageKey = "galleries/$galleryId/template-${TestSequence.next()}.png",
            originalFileName = "template-$displayOrder.png",
            contentType = "image/png",
            displayOrder = displayOrder,
        )
}
