package com.soma.wes.photo.support

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 화면 규칙의 단일 소유자인 조립자를 그 경계에서 확인한다.
 *
 * 보는 것은 셋이다: PENDING이면 URL을 주지 않는다는 규칙이 조회·원본 양쪽에 같게 걸리는지,
 * 파생본 유무에 따라 viewUrl이 갈리고 originalUrl은 언제나 원본인지, 그리고 별점이
 * 단건·배치에서 같게 붙고 게스트 응답에서는 빠지는지.
 */
@IntegrationTest
class PhotoViewAssemblerTest @Autowired constructor(
    private val photoViewAssembler: PhotoViewAssembler,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("조회 URL을 만들 때")
    inner class ViewUrl {

        @Test
        fun `올라온 사진은 서명된 URL을 받는다`() {
            // given
            val photo = photo(photoFixture.업로드된_사진(fixture.galleryId, count = 1).first())

            // when
            val result = photoViewAssembler.toResponse(photo)

            // then
            assertSoftly { softly ->
                // 버킷이 비공개라 서명 없는 URL로는 이미지를 띄울 수 없다.
                softly.assertThat(result.viewUrl).contains("X-Amz-Signature")
                // 파생본이 아직 없으면 원본이 화면용 URL이다.
                softly.assertThat(result.viewUrl).contains(photo.storageKey)
                softly.assertThat(result.previewReady).isFalse()
            }
        }

        @Test
        fun `아직 올라오지 않은 사진에는 URL을 주지 않는다`() {
            // PENDING은 서명 URL만 발급됐을 뿐 S3에 객체가 없을 수 있다. URL을 주면
            // <img>가 깨진 이미지를 그린다. 두 URL이 같은 규칙을 따라야 화면이 나란히 쓸 수 있다.
            // given
            val photo = photo(photoFixture.대기중_사진(fixture.galleryId, count = 1).first())

            // when & then
            assertSoftly { softly ->
                softly.assertThat(photoViewAssembler.toResponse(photo).viewUrl).isNull()
                softly.assertThat(photoViewAssembler.viewUrlOf(photo)).isNull()
                softly.assertThat(photoViewAssembler.originalUrlOf(photo)).isNull()
            }
        }

        @Test
        fun `파생본이 있으면 원본이 아니라 파생본을 가리킨다`() {
            // 아이폰 원본(HEIC)은 주요 브라우저가 그리지 못한다. Lambda가 만들어 둔
            // JPEG 파생본이 있으면 화면용 URL은 그쪽이다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            val previewKey = attachPreview(photoId)
            val photo = photo(photoId)

            // when
            val result = photoViewAssembler.toResponse(photo)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.previewReady).isTrue()
                softly.assertThat(result.viewUrl).contains(previewKey)
                softly.assertThat(result.viewUrl).contains("X-Amz-Signature")
                // storageKey는 그대로 원본이다. 파생본은 화면용일 뿐 원본을 대신하지 않는다.
                softly.assertThat(result.storageKey).isEqualTo(photo.storageKey)
            }
        }
    }

    @Nested
    @DisplayName("원본 URL을 만들 때")
    inner class OriginalUrl {

        @Test
        fun `파생본이 있어도 원본 URL은 언제나 원본을 가리킨다`() {
            // 파생본은 줄어든 이미지라 확대하면 뭉개진다. 상세 화면은 둘 다 받아서 고른다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            attachPreview(photoId)
            val photo = photo(photoId)

            // when
            val originalUrl = photoViewAssembler.originalUrlOf(photo)

            // then
            assertSoftly { softly ->
                softly.assertThat(originalUrl).contains(photo.storageKey)
                softly.assertThat(originalUrl).doesNotContain("previews/")
                softly.assertThat(originalUrl).contains("X-Amz-Signature")
            }
        }

        @Test
        fun `원본 URL은 조회 URL보다 오래 산다`() {
            // 상세는 한 장을 오래 열어두는 화면이다. 목록용 수명으로 서명하면 확대해 보는
            // 도중에 만료되고, 그때 S3의 403은 사용자에게 깨진 사진으로 보인다.
            // given
            val photo = photo(photoFixture.업로드된_사진(fixture.galleryId, count = 1).first())

            // when & then
            assertThat(photoViewAssembler.viewUrlOf(photo)).contains("X-Amz-Expires=900")
            assertThat(photoViewAssembler.originalUrlOf(photo)).contains("X-Amz-Expires=3600")
        }
    }

    @Nested
    @DisplayName("별점을 붙일 때")
    inner class AttachScore {

        @Test
        fun `매겨진 별점이 실려 오고 없으면 null이다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            rate(photoIds[0], score = 4)

            // when & then
            assertSoftly { softly ->
                softly.assertThat(photoViewAssembler.toResponse(photo(photoIds[0])).score).isEqualTo(4)
                softly.assertThat(photoViewAssembler.toResponse(photo(photoIds[1])).score).isNull()
                softly.assertThat(photoViewAssembler.scoreOf(photo(photoIds[0]))).isEqualTo(4)
            }
        }

        @Test
        fun `단건과 배치가 같은 별점을 붙인다`() {
            // 배치는 별점을 한 번에 읽는 최적화일 뿐, 화면에 보이는 결과가 단건과 갈리면 안 된다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            rate(photoIds[0], score = 5)
            rate(photoIds[2], score = 2)
            val photos = photoIds.map { photo(it) }

            // when
            val batch = photoViewAssembler.toResponses(photos)
            val singles = photos.map { photoViewAssembler.toResponse(it) }

            // then
            assertThat(batch.map { it.photoId to it.score })
                .isEqualTo(singles.map { it.photoId to it.score })
            assertSoftly { softly ->
                softly.assertThat(batch.map { it.score }).containsExactly(5, null, 2)
                softly.assertThat(batch.map { it.photoId }).isEqualTo(photoIds)
                softly.assertThat(batch).allSatisfy { response ->
                    assertThat(response.viewUrl).contains("X-Amz-Signature")
                }
            }
        }

        @Test
        fun `빈 목록이면 빈 응답이다`() {
            // when
            val result = photoViewAssembler.toResponses(emptyList())

            // then
            assertThat(result).isEmpty()
        }
    }

    @Nested
    @DisplayName("게스트 응답을 만들 때")
    inner class AnonymousResponses {

        @Test
        fun `별점은 숨기고 조회 URL은 그대로 준다`() {
            // 별점은 부부와 작가가 서로에게 하는 말이다. 사진에 찍힌 하객이
            // 자기 사진의 점수를 읽게 되면 안 된다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            rate(photoIds[0], score = 5)
            val photos = photoIds.map { photo(it) }

            // when
            val result = photoViewAssembler.toAnonymousResponses(photos)

            // then
            assertSoftly { softly ->
                softly.assertThat(result).hasSize(2)
                softly.assertThat(result.map { it.score }).containsOnlyNulls()
                // 숨기는 것은 별점뿐이다. 사진 자체는 게스트도 봐야 한다.
                softly.assertThat(result[0].viewUrl).contains("X-Amz-Signature")
            }
            // 점수가 지워진 것이 아니라 응답에서만 빠졌다.
            assertThat(photoRatingRepository.count()).isEqualTo(1L)
        }
    }

    // --- helpers ---

    private fun photo(photoId: Long): Photo = photoRepository.findById(photoId).orElseThrow()

    private fun rate(photoId: Long, score: Int) {
        photoRatingRepository.save(
            PhotoRating.of(photoId = photoId, score = score, ratedBy = fixture.member.id!!),
        )
    }

    /**
     * 임베딩 Lambda가 파생본을 올린 상태를 만들고 파생본 키를 돌려준다.
     *
     * 파생본 키 규칙(`previews/{원본 키}`)은 Lambda의 `images.preview_key_for`가 정한다.
     */
    private fun attachPreview(photoId: Long): String {
        val photo = photoRepository.findById(photoId).orElseThrow()
        photo.previewKey = "previews/${photo.storageKey}"
        photoRepository.saveAndFlush(photo)
        return photo.previewKey!!
    }
}
