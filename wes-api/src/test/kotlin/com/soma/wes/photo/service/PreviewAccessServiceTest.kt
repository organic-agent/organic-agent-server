package com.soma.wes.photo.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.SwitchablePreviewCdn
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 미리보기 접근 쿠키는 갤러리를 볼 수 있는 사람에게만, 그 갤러리 경로로만 나간다(#336 ADR-0001 · 계약 05).
 */
@IntegrationTest
class PreviewAccessServiceTest @Autowired constructor(
    private val previewAccessService: PreviewAccessService,
    private val previewCdn: SwitchablePreviewCdn,
    private val galleryFixture: GalleryFixture,
    private val userFixture: UserFixture,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
        previewCdn.turnOn()
    }

    @AfterEach
    fun turnOffCdn() {
        previewCdn.turnOff()
    }

    @Test
    fun `부부와 작가는 그 갤러리 미리보기 경로의 쿠키를 받는다`() {
        // when
        val forMember = previewAccessService.issue(fixture.galleryId, fixture.member.id!!)
        val forPhotographer = previewAccessService.issue(fixture.galleryId, fixture.photographer.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(forMember.cookies).hasSize(3)
            softly.assertThat(forMember.cookies.map { it.path }.toSet()).containsExactly("/previews/galleries/${fixture.galleryId}/")
            softly.assertThat(forMember.refreshAfterSeconds).isEqualTo(600)
            softly.assertThat(forPhotographer.cookies).hasSize(3)
        }
    }

    @Test
    fun `갤러리를 볼 수 없는 사람은 쿠키를 받지 못한다`() {
        // 쿠키를 주면 주소를 아는 사람 누구나 그 갤러리 미리보기를 열 수 있게 된다.
        // given
        val outsider = userFixture.사용자()

        // when & then
        assertThatThrownBy { previewAccessService.issue(fixture.galleryId, outsider.id!!) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }

    @Test
    fun `없는 갤러리는 404다`() {
        assertThatThrownBy { previewAccessService.issue(Long.MAX_VALUE, fixture.member.id!!) }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
    }

    @Test
    fun `CDN이 꺼져 있으면 쿠키 없이 같은 모양을 준다`() {
        // 웹이 CDN 켜짐/꺼짐을 따로 분기하지 않게 같은 응답 모양을 유지한다.
        // given
        previewCdn.turnOff()

        // when
        val result = previewAccessService.issue(fixture.galleryId, fixture.member.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(result.cookies).isEmpty()
            softly.assertThat(result.expiresAt).isNull()
            softly.assertThat(result.refreshAfterSeconds).isNull()
        }
    }

    @Test
    fun `쿠키 값은 갤러리마다 다르다`() {
        // 정책(Resource)에 갤러리 경로가 들어가므로 다른 갤러리의 쿠키를 재사용할 수 없다.
        // given
        val other = galleryFixture.멤버와_열린_갤러리()

        // when
        val mine = previewAccessService.issue(fixture.galleryId, fixture.member.id!!).cookies.first().value
        val theirs = previewAccessService.issue(other.galleryId, other.member.id!!).cookies.first().value

        // then
        assertThat(mine).isNotEqualTo(theirs)
    }
}
