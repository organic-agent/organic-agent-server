package com.soma.wes.photo.support

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.SwitchablePreviewCdn
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 미리보기 CDN이 켜졌을 때 보기 주소가 누구에게 어떻게 나가는지(#336 ADR-0001).
 *
 * 부부·작가의 미리보기만 고정 주소이고, 하객 · 미리보기 없는 사진 · `preview=signed`로 물러난 요청은 지금처럼 서명 URL이어야 한다.
 * 하객에게 고정 주소를 주면 갤러리 경로 쿠키 없이는 403이고, 쿠키를 주면 공유 안 된 사진까지 열린다 — 둘 다 막는 것이 이 규칙이다.
 */
@IntegrationTest
class PhotoViewAssemblerCdnTest @Autowired constructor(
    private val photoViewAssembler: PhotoViewAssembler,
    private val previewDelivery: PreviewDelivery,
    private val previewCdn: SwitchablePreviewCdn,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
        previewCdn.turnOn()
    }

    @AfterEach
    fun restore() {
        previewCdn.turnOff()
        previewDelivery.clear()
    }

    @Nested
    @DisplayName("부부·작가 화면의 보기 주소를 만들 때")
    inner class MemberView {

        @Test
        fun `미리보기는 만료 없는 고정 주소로 나간다`() {
            // given
            val photo = photoWithPreview()

            // when
            val viewUrl = photoViewAssembler.viewUrlOf(photo)

            // then
            assertSoftly { softly ->
                softly.assertThat(viewUrl).isEqualTo("${SwitchablePreviewCdn.BASE_URL}/${photo.previewKey}")
                // 주소에 서명·만료가 없어야 브라우저가 한 번 받은 사진을 계속 다시 쓴다.
                softly.assertThat(viewUrl).doesNotContain("X-Amz-")
            }
        }

        @Test
        fun `미리보기가 없는 사진은 원본을 서명 URL로 준다`() {
            // 쿠키는 미리보기 경로만 연다. 원본을 고정 주소로 주면 열리지 않는다.
            // given
            val photo = photo(photoFixture.업로드된_사진(fixture.galleryId, count = 1).first())

            // when & then
            assertThat(photoViewAssembler.viewUrlOf(photo)).contains("X-Amz-Signature").contains(photo.storageKey)
        }

        @Test
        fun `preview=signed 로 물러난 요청은 미리보기도 서명 URL로 준다`() {
            // 쿠키가 실리지 않는 인앱 브라우저에서 화면이 깨지지 않게 하는 문이다.
            // given
            val photo = photoWithPreview()
            previewDelivery.forceSigned(true)

            // when & then
            assertThat(photoViewAssembler.viewUrlOf(photo)).contains("X-Amz-Signature").contains(photo.previewKey)
        }

        @Test
        fun `원본 URL은 CDN과 무관하게 짧은 서명 URL이다`() {
            // given
            val photo = photoWithPreview()

            // when & then
            assertThat(photoViewAssembler.originalUrlOf(photo)).contains("X-Amz-Signature").contains(photo.storageKey)
        }
    }

    @Nested
    @DisplayName("하객 화면의 보기 주소를 만들 때")
    inner class GuestView {

        @Test
        fun `미리보기가 있어도 서명 URL로 준다`() {
            // 하객은 공유된 사진만 본다 — 갤러리 경로 쿠키를 줄 수 없으니 고정 주소도 줄 수 없다.
            // given
            val photo = photoWithPreview()

            // when
            val result = photoViewAssembler.toAnonymousResponses(listOf(photo)).single()

            // then
            assertThat(result.viewUrl).contains("X-Amz-Signature").contains(photo.previewKey)
        }
    }

    @Nested
    @DisplayName("주소가 만료되는지 알릴 때")
    inner class Expiry {

        @Test
        fun `고정 주소만 있으면 만료되지 않는다고 알린다`() {
            // 웹은 이 값이 false면 12분 갱신(목록 다시 읽기)을 하지 않는다.
            // given
            val responses = photoViewAssembler.toResponses(listOf(photoWithPreview(), photoWithPreview()))

            // when & then
            assertThat(photoViewAssembler.viewUrlsExpire(responses.map { it.viewUrl })).isFalse()
        }

        @Test
        fun `서명 URL이 하나라도 섞이면 만료된다고 알린다`() {
            // given
            val withoutPreview = photo(photoFixture.업로드된_사진(fixture.galleryId, count = 1).first())
            val responses = photoViewAssembler.toResponses(listOf(photoWithPreview(), withoutPreview))

            // when & then
            assertThat(photoViewAssembler.viewUrlsExpire(responses.map { it.viewUrl })).isTrue()
        }
    }

    private fun photo(photoId: Long): Photo = photoRepository.findById(photoId).orElseThrow()

    /** 업로드된 사진에 임베더가 만든 것처럼 미리보기 키를 붙인다. */
    private fun photoWithPreview(): Photo {
        val photo = photo(photoFixture.업로드된_사진(fixture.galleryId, count = 1).first())
        photo.previewKey = "previews/${photo.storageKey}"
        return photoRepository.saveAndFlush(photo)
    }
}
