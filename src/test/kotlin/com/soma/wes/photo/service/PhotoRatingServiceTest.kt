package com.soma.wes.photo.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import org.springframework.jdbc.core.JdbcTemplate
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

/**
 * 사진 별점을 서비스 경계에서 확인한다.
 *
 * 핵심은 "점수가 사진당 하나"라는 것이다 — 부부 두 사람이 같은 한 칸을 나눠 쓰고,
 * 누가 매겼는지로 행이 나뉘지 않는다.
 */
@IntegrationTest
class PhotoRatingServiceTest @Autowired constructor(
    private val photoRatingService: PhotoRatingService,
    private val photoService: PhotoService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val photoRatingRepository: PhotoRatingRepository,
    private val galleries: GalleryRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val jdbc: JdbcTemplate,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("별점을 매길 때")
    inner class Rate {

        @Test
        fun `부부가 매기면 상세에 실려 온다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when
            val result = photoRatingService.rate(
                fixture.galleryId, photoId, fixture.member.id!!, RatePhotoRequest(score = 4),
            )

            // then
            assertThat(result.score).isEqualTo(4)
            assertThat(result.ratedBy).isEqualTo(fixture.member.id!!)

            val detail = photoService.get(fixture.galleryId, photoId, fixture.member.id!!)
            assertThat(detail.score).isEqualTo(4)
        }

        @Test
        fun `STUDIO 관리자는 별점을 매길 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            assertThatThrownBy {
                photoRatingService.rate(
                    fixture.galleryId, photoId, fixture.photographer.id!!, RatePhotoRequest(score = 5),
                )
            }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `다시 매기면 행이 늘지 않고 덮어써진다`() {
            // 사진당 한 행이 이 도메인의 전부다. 응답만 보면 행이 두 벌 쌓였는지 알 수 없다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            rate(photoId, score = 2)

            // when
            val result = photoRatingService.rate(
                fixture.galleryId, photoId, fixture.member.id!!, RatePhotoRequest(score = 5),
            )

            // then
            assertThat(result.score).isEqualTo(5)
            // 마지막에 매긴 사람으로 바뀐다.
            assertThat(result.ratedBy).isEqualTo(fixture.member.id!!)
            assertThat(photoRatingRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `범위를 벗어난 점수는 거부한다`() {
            // HTTP 경계에서는 컨트롤러의 @Min·@Max가 먼저 걸러 GLOBAL 코드가 나간다. 서비스를
            // 직접 부르면 그 검증이 돌지 않으므로, 도메인의 두 번째 방어선(PhotoRating의
            // 점수 검증)이 PHOTO_400_4를 던지는 것을 여기서 확인한다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when & then
            listOf(0, 6).forEach { score ->
                assertThatThrownBy {
                    photoRatingService.rate(
                        fixture.galleryId, photoId, fixture.member.id!!, RatePhotoRequest(score = score),
                    )
                }
                    .isInstanceOf(PhotoException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(PhotoErrorCode.INVALID_SCORE)
            }

            assertThat(photoRatingRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `다른 갤러리의 사진에는 매길 수 없다`() {
            // 인가는 galleryId로 끝난다. 사진을 id만으로 찾으면 그 확인이 무의미해진다.
            // given
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoId = photoFixture.업로드된_사진(otherFixture.galleryId, count = 1).first()

            // when & then
            assertThatThrownBy {
                photoRatingService.rate(
                    fixture.galleryId, otherPhotoId, fixture.member.id!!, RatePhotoRequest(score = 5),
                )
            }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.PHOTO_NOT_FOUND)
        }

        @Test
        fun `갤러리와 무관한 사용자는 매길 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy {
                photoRatingService.rate(
                    fixture.galleryId, photoId, stranger.id!!, RatePhotoRequest(score = 5),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `마감이 지나면 초대 멤버는 매길 수 없다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThatThrownBy {
                photoRatingService.rate(
                    fixture.galleryId, photoId, fixture.member.id!!, RatePhotoRequest(score = 5),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)

            assertThat(photoRatingRepository.count()).isZero()
        }
    }

    @Nested
    @DisplayName("별점을 지울 때")
    inner class Remove {

        @Test
        fun `지우면 상세에서도 사라진다`() {
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()
            rate(photoId, score = 3)

            // when
            photoRatingService.clear(fixture.galleryId, photoId, fixture.member.id!!)

            // then
            assertThat(photoRatingRepository.count()).isEqualTo(0L)
            val detail = photoService.get(fixture.galleryId, photoId, fixture.member.id!!)
            assertThat(detail.score).isNull()
        }

        @Test
        fun `매긴 적 없어도 성공한다`() {
            // 만들려는 상태(점수 없음)가 이미 그것이라 다시 보내도 결과가 같다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).first()

            // when
            photoRatingService.clear(fixture.galleryId, photoId, fixture.member.id!!)

            // then
            assertThat(photoRatingRepository.count()).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("목록에서 별점으로 거를 때")
    inner class FilterByScore {

        @Test
        fun `스튜디오는 고객 별점을 볼 수도 필터로 추론할 수도 없다`() {
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            rate(photoId, score = 5)

            val list = photoService.list(fixture.galleryId, fixture.photographer.requiredId, null, null, page = 0, size = 20)
            assertThat(list.contents.single().score).isNull()
            assertThat(photoService.get(fixture.galleryId, photoId, fixture.photographer.requiredId).score).isNull()
            assertThatThrownBy {
                photoService.list(fixture.galleryId, fixture.photographer.requiredId, null, 4, page = 0, size = 20)
            }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `목록에 별점이 함께 오고 최소 점수로 거를 수 있다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            rate(photoIds[0], score = 5)
            rate(photoIds[1], score = 3)

            // when
            val all = list()
            val filtered = list(minScore = 4)

            // then
            assertSoftly { softly ->
                softly.assertThat(all.contents).hasSize(3)
                softly.assertThat(all.contents[0].score).isEqualTo(5)
                softly.assertThat(all.contents[1].score).isEqualTo(3)
                // 아무도 매기지 않은 사진은 null이다.
                softly.assertThat(all.contents[2].score).isNull()
            }
            assertSoftly { softly ->
                // 별점이 없는 사진도 함께 빠진다.
                softly.assertThat(filtered.contents).hasSize(1)
                softly.assertThat(filtered.contents[0].photoId).isEqualTo(photoIds[0])
                softly.assertThat(filtered.totalCount).isEqualTo(1L)
            }
        }

        @Test
        fun `범위를 벗어난 minScore는 거부한다`() {
            // 조용히 빈 목록을 주면 화면에는 "고른 사진이 없다"로 보인다.
            // when & then
            assertThatThrownBy { list(minScore = 9) }
                .isInstanceOf(PhotoException::class.java)
                .extracting("errorCode")
                .isEqualTo(PhotoErrorCode.INVALID_SCORE)
        }

        @Test
        fun `상태와 최소 점수를 함께 걸 수 있다`() {
            // given
            val uploadedIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val pendingId = photoFixture.대기중_사진(fixture.galleryId, count = 1).first()
            rate(uploadedIds[0], score = 5)
            rate(pendingId, score = 5)

            // when
            val result = list(status = PhotoStatus.UPLOADED, minScore = 4)

            // then
            assertThat(result.contents).hasSize(1)
            assertThat(result.contents[0].photoId).isEqualTo(uploadedIds[0])
        }
    }

    @Test
    fun `고객이 스튜디오에 합류하면 중복 소속이나 운영 정지로 별점 권한이 되살아나지 않는다`() {
        val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
        rate(photoId, 5)
        val gallery = galleries.findById(fixture.galleryId).orElseThrow()
        workspaceMembers.save(WorkspaceMember(gallery.workspaceId, fixture.member.requiredId, WorkspaceRole.MEMBER))

        fun verifyPrivacy() {
            assertThat(photoService.get(fixture.galleryId, photoId, fixture.member.requiredId).score).isNull()
            assertThatThrownBy { rate(photoId, 4) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        verifyPrivacy()
        jdbc.update("update studios set suspended_at = now() where workspace_id = ?", gallery.workspaceId)
        verifyPrivacy()
    }

    // --- helpers ---

    private fun rate(photoId: Long, score: Int) {
        photoRatingService.rate(fixture.galleryId, photoId, fixture.member.id!!, RatePhotoRequest(score = score))
    }

    private fun list(status: PhotoStatus? = null, minScore: Int? = null) =
        photoService.list(fixture.galleryId, fixture.member.id!!, status, minScore, page = 0, size = 20)
}
