package com.soma.wes.retouch.service

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.retouch.domain.RetouchPoint
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.support.RetouchPdfSnapshotLoader
import com.soma.wes.support.IntegrationTest
import com.soma.wes.support.FakePreviewImageReader
import com.soma.wes.user.fixture.UserFixture
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@IntegrationTest
class RetouchPdfServiceTest @Autowired constructor(
    private val service: RetouchPdfService,
    private val loader: RetouchPdfSnapshotLoader,
    private val galleries: GalleryFixture,
    private val personalGalleries: PersonalGalleryFixture,
    private val photos: PhotoFixture,
    private val rounds: RetouchFixture,
    private val items: RetouchPhotoRepository,
    private val previews: FakePreviewImageReader,
    private val users: UserFixture,
) {
    @BeforeEach
    fun reset() = previews.reset()

    @Nested
    @DisplayName("갤러리 공개 규칙을 적용할 때")
    inner class Access {
        @Test
        fun `무관한 사용자는 저장된 요청을 내려받을 수 없다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val outsider = users.사용자()
            // when & then
            assertThatThrownBy { service.download(gallery.galleryId, 1, outsider.requiredId, "all") }
                .isInstanceOf(GalleryException::class.java).extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `작가는 미제출 초안을 받지 못하고 부부는 받을 수 있다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            rounds.초안_회차(gallery.galleryId, photoIds = listOf(photo.requiredId))
            previews.put(checkNotNull(photo.previewKey), jpeg())
            // when & then
            assertThatThrownBy { service.download(gallery.galleryId, 1, gallery.photographer.requiredId, "all") }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                .isEqualTo(RetouchErrorCode.EMPTY_PDF)
            assertThat(service.download(gallery.galleryId, 1, gallery.member.requiredId, "all").bytes).isNotEmpty()
        }

        @Test
        fun `개인 갤러리 소유자와 파트너는 저장한 초안을 받는다`() {
            // given
            val gallery = personalGalleries.파트너와_개인_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            rounds.초안_회차(gallery.galleryId, photoIds = listOf(photo.requiredId))
            previews.put(checkNotNull(photo.previewKey), jpeg())
            // when & then
            for (user in listOf(gallery.ownerId, gallery.partnerId)) {
                assertThat(service.download(gallery.galleryId, 1, user, "all").bytes).isNotEmpty()
            }
        }
    }

    @Nested
    @DisplayName("회차와 범위를 선택할 때")
    inner class Scope {
        @Test
        fun `다른 회차 요청을 섞지 않고 채택한 문장만 출력한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            val first = rounds.완료된_회차(gallery.galleryId, roundNo = 1, photoIds = listOf(photo.requiredId))
            val second = rounds.제출된_회차(gallery.galleryId, roundNo = 2, photoIds = listOf(photo.requiredId))
            val oldItem = items.findAllByRoundId(first.requiredId).single()
            oldItem.writeRequest("이전 회차 문장")
            val item = items.findAllByRoundId(second.requiredId).single()
            item.writeRequest("이번 회차 문장", listOf(RetouchPoint(x = .3, y = .7, text = "채택 전 원문", refinedText = "채택한 문장", useRefinedText = true)))
            items.saveAllAndFlush(listOf(oldItem, item))
            previews.put(checkNotNull(photo.previewKey), jpeg())
            // when
            val result = service.download(gallery.galleryId, 2, gallery.photographer.requiredId, "memo")
            // then
            Loader.loadPDF(result.bytes).use { document ->
                assertThat(PDFTextStripper().getText(document)).contains("이번 회차 문장", "채택한 문장")
                    .doesNotContain("이전 회차 문장", "채택 전 원문", "WES", "2차 보정", "채택한 정제 문장", "사진 전체 요청")
            }
        }

        @Test
        fun `결과 없는 범위는 열람자에게 공개된 결과만 기준으로 삼는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            val item = rounds.결과_없는_제출된_회차(gallery.galleryId, photoIds = listOf(photo.requiredId)).single()
            item.writeResult("galleries/${gallery.galleryId}/retouch/results/1/result.jpg", "image/jpeg")
            items.saveAndFlush(item)
            // when & then
            assertThatThrownBy { loader.load(gallery.galleryId, 1, gallery.photographer.requiredId, "noResult") }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode").isEqualTo(RetouchErrorCode.EMPTY_PDF)
            assertThat(loader.load(gallery.galleryId, 1, gallery.member.requiredId, "noResult").photos).hasSize(1)
        }

        @Test
        fun `빈 메모 범위와 잘못된 범위를 거절한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            rounds.제출된_회차(gallery.galleryId, photoIds = listOf(photo.requiredId))
            // when & then
            assertThatThrownBy { loader.load(gallery.galleryId, 1, gallery.member.requiredId, "memo") }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode").isEqualTo(RetouchErrorCode.EMPTY_PDF)
            assertThatThrownBy { loader.load(gallery.galleryId, 1, gallery.member.requiredId, "unknown") }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode").isEqualTo(RetouchErrorCode.INVALID_PDF_SCOPE)
        }
    }

    @Nested
    @DisplayName("이미지를 준비할 때")
    inner class Image {
        @Test
        fun `미리보기 없이 원본으로 대신 생성하지 않는다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.업로드된_사진(gallery.galleryId, 1).single()
            rounds.제출된_회차(gallery.galleryId, photoIds = listOf(photo))
            // when & then
            assertThatThrownBy { service.download(gallery.galleryId, 1, gallery.member.requiredId, "all") }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PDF_PREVIEW_NOT_READY)
        }

        @Test
        fun `스토리지 오류 뒤에도 생성 슬롯을 반환한다`() {
            // given
            val gallery = galleries.멤버와_열린_갤러리()
            val photo = photos.미리보기_있는_사진(gallery.galleryId)
            rounds.제출된_회차(gallery.galleryId, photoIds = listOf(photo.requiredId))
            val key = checkNotNull(photo.previewKey)
            previews.fail(key)
            // when & then
            assertThatThrownBy { service.download(gallery.galleryId, 1, gallery.member.requiredId, "all") }
                .isInstanceOf(PhotoException::class.java).extracting("errorCode").isEqualTo(PhotoErrorCode.STORAGE_READ_FAILED)
            previews.reset()
            previews.put(key, jpeg())
            assertThat(service.download(gallery.galleryId, 1, gallery.member.requiredId, "all").bytes).isNotEmpty()
        }
    }

    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB), "jpeg", output)
        output.toByteArray()
    }
}
