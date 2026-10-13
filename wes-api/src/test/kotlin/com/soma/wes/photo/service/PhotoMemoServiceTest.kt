package com.soma.wes.photo.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGallery
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.photo.domain.PhotoMemo
import com.soma.wes.photo.dto.request.WritePhotoMemoRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoMemoRepository
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

/**
 * 사진 메모를 서비스 경계에서 확인한다.
 *
 * 핵심은 둘이다 — 메모가 사진당 하나라 소유자와 파트너가 같은 칸을 고친다는 것,
 * 그리고 지금은 개인 갤러리의 두 사람만 읽고 쓴다는 것.
 */
@IntegrationTest
class PhotoMemoServiceTest @Autowired constructor(
    private val photoMemoService: PhotoMemoService,
    private val photoService: PhotoService,
    private val galleryFixture: GalleryFixture,
    private val personalGalleryFixture: PersonalGalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val photoMemoRepository: PhotoMemoRepository,
    private val jdbc: JdbcTemplate,
) {

    private lateinit var gallery: PersonalGallery

    @BeforeEach
    fun setUpBaseData() {
        gallery = personalGalleryFixture.파트너와_개인_갤러리()
    }

    @Nested
    @DisplayName("메모를 적을 때")
    inner class Write {

        @Test
        fun `소유자가 적으면 상세와 목록에 실려 온다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()

            // when
            val result = photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest("엄마가 좋아하실 컷"))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.content).isEqualTo("엄마가 좋아하실 컷")
                softly.assertThat(result.updatedBy).isEqualTo(gallery.ownerId)
                softly.assertThat(result.updatedAt).isNotNull()
                softly.assertThat(photoService.get(gallery.galleryId, photoId, gallery.partnerId).memo?.content)
                    .isEqualTo("엄마가 좋아하실 컷")
                softly.assertThat(
                    photoService.list(gallery.galleryId, gallery.partnerId, null, null, 0, 50).contents.single().memo?.content,
                ).isEqualTo("엄마가 좋아하실 컷")
            }
        }

        @Test
        fun `파트너가 다시 적으면 같은 한 칸을 덮어쓴다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest("처음"))

            // when
            val result = photoMemoService.write(gallery.galleryId, photoId, gallery.partnerId, WritePhotoMemoRequest("고친 메모"))

            // then
            assertThat(result.content).isEqualTo("고친 메모")
            assertThat(result.updatedBy).isEqualTo(gallery.partnerId)
            assertThat(photoMemoRepository.findAllByPhotoIdIn(listOf(photoId))).hasSize(1)
        }

        @Test
        fun `메모가 없는 사진은 null로 온다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()

            // when
            val detail = photoService.get(gallery.galleryId, photoId, gallery.ownerId)

            // then
            assertThat(detail.memo).isNull()
        }

        @Test
        fun `비었거나 상한을 넘은 메모는 거절된다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()

            // when & then
            for (content in listOf("  ", "가".repeat(PhotoMemo.MAX_CONTENT_LENGTH + 1))) {
                assertThatThrownBy {
                    photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest(content))
                }
                    .isInstanceOf(PhotoException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(PhotoErrorCode.INVALID_MEMO)
            }
        }

        @Test
        fun `다른 갤러리의 사진이면 404다`() {
            // given
            val other = personalGalleryFixture.파트너와_개인_갤러리()
            val photoId = photoFixture.업로드된_사진(other.galleryId, count = 1).single()

            // when & then
            assertThatThrownBy {
                photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest("메모"))
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
        }

        @Test
        fun `참여자가 아니면 적을 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy {
                photoMemoService.write(gallery.galleryId, photoId, stranger.requiredId, WritePhotoMemoRequest("메모"))
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `초대 갤러리에서는 부부도 아직 쓸 수 없다`() {
            // given
            val invited = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(invited.galleryId, count = 1).single()

            // when & then
            for (userId in listOf(invited.member.requiredId, invited.photographer.requiredId)) {
                assertThatThrownBy {
                    photoMemoService.write(invited.galleryId, photoId, userId, WritePhotoMemoRequest("메모"))
                }
                    .isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }

        @Test
        fun `보관된 갤러리에는 적을 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            jdbc.update("UPDATE galleries SET stage = 'ARCHIVED' WHERE id = ?", gallery.galleryId)

            // when & then
            assertThatThrownBy {
                photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest("메모"))
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
        }
    }

    @Nested
    @DisplayName("메모를 지울 때")
    inner class Clear {

        @Test
        fun `지우면 응답에서 사라지고 다시 지워도 성공한다`() {
            // given
            val photoId = photoFixture.업로드된_사진(gallery.galleryId, count = 1).single()
            photoMemoService.write(gallery.galleryId, photoId, gallery.ownerId, WritePhotoMemoRequest("메모"))

            // when
            photoMemoService.clear(gallery.galleryId, photoId, gallery.partnerId)
            photoMemoService.clear(gallery.galleryId, photoId, gallery.partnerId)

            // then
            assertThat(photoService.get(gallery.galleryId, photoId, gallery.ownerId).memo).isNull()
        }
    }
}
