package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.ChangeMaxRetouchRoundCountRequest
import com.soma.wes.gallery.dto.request.ChangeMaxSelectablePhotoCountRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.domain.User
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

/**
 * 갤러리의 생애를 서비스 경계에서 확인한다.
 *
 * 보는 것은 셋이다: DRAFT로 시작해 열고 닫는 상태 전이가 규칙대로 도는지, 누구에게 무엇이
 * 보이는지(작가는 자기 스튜디오, 부부는 열린 갤러리만), 그리고 계약 장수가 작가의 손에만
 * 있고 줄어들어도 부부의 화면이 깨지지 않는지.
 */
@IntegrationTest
class GalleryServiceTest @Autowired constructor(
    private val galleryService: GalleryService,
    private val photoSelectionService: PhotoSelectionService,
    private val userFixture: UserFixture,
    private val studioFixture: StudioFixture,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val galleryMemberRepository: GalleryMemberRepository,
) {

    @Nested
    @DisplayName("갤러리를 만들 때")
    inner class Create {

        @Test
        fun `작가가 만들면 DRAFT로 시작한다`() {
            // 사진을 올리고 정리하는 동안 초대된 사람에게 보이면 안 된다. 여는 시점은 작가가 정한다.
            // given
            val photographer = studioFixture.작가()

            // when
            val result = galleryService.create(
                photographer.id!!, CreateGalleryRequest(title = "김철수 · 이영희 본식"),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.id).isNotNull()
                softly.assertThat(result.title).isEqualTo("김철수 · 이영희 본식")
                softly.assertThat(result.status).isEqualTo(GalleryStatus.DRAFT)
                softly.assertThat(result.selectionDeadline).isNull()
            }
        }

        @Test
        fun `온보딩을 마치지 않은 사용자는 만들 수 없다`() {
            // 스튜디오가 없다는 것은 아직 작가가 아니라는 뜻이다. 갤러리는 작가 개인이 아니라
            // 스튜디오에 속하므로 붙일 곳 자체가 없다.
            // given
            val notOnboarded = userFixture.사용자()

            // when & then
            assertThatThrownBy {
                galleryService.create(notOnboarded.id!!, CreateGalleryRequest(title = "갤러리"))
            }
                .isInstanceOf(StudioException::class.java)
                .extracting("errorCode")
                .isEqualTo(StudioErrorCode.STUDIO_NOT_FOUND)
        }

        @Test
        fun `제목이 비면 만들 수 없다`() {
            // 컨트롤러의 @Valid(GLOBAL_400_2)는 서비스 직접 호출에서는 돌지 않는다.
            // 이 경로의 유일한 검증은 도메인 관문(Gallery.create의 require)이다.
            // given
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy {
                galleryService.create(photographer.id!!, CreateGalleryRequest(title = "  "))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `이미 지난 마감 기한으로는 만들 수 없다`() {
            // 만들자마자 아무도 못 고르는 갤러리가 된다. 작가가 알아챌 수 있는 지점은 여기뿐이다.
            // given
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy {
                galleryService.create(
                    photographer.id!!,
                    CreateGalleryRequest(title = "본식", selectionDeadline = ZonedDateTime.now().minusDays(1)),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_SELECTION_DEADLINE)
        }
    }

    @Nested
    @DisplayName("갤러리를 조회할 때")
    inner class Read {

        @Test
        fun `작가 목록에는 자기 스튜디오의 갤러리만 나온다`() {
            // given
            val mine = studioFixture.작가()
            val other = studioFixture.작가()
            createGallery(mine, "내 갤러리")
            createGallery(other, "남의 갤러리")

            // when
            val result = galleryService.findAllVisibleTo(mine.id!!)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].title).isEqualTo("내 갤러리")
        }

        @Test
        fun `부부 목록에는 아직 열리지 않은 갤러리가 나오지 않는다`() {
            // given
            val photographer = studioFixture.작가()
            val draftId = createGallery(photographer, "정리 중")
            val openedId = createGallery(photographer, "열린 갤러리")
            galleryService.open(openedId, photographer.id!!)

            val member = userFixture.사용자()
            galleryMemberRepository.save(GalleryMember(galleryId = draftId, userId = member.id!!))
            galleryMemberRepository.save(GalleryMember(galleryId = openedId, userId = member.id!!))

            // when
            val result = galleryService.findAllVisibleTo(member.id!!)

            // then
            assertThat(result).hasSize(1)
            assertThat(result[0].title).isEqualTo("열린 갤러리")
        }

        @Test
        fun `초대받지 않은 사용자는 조회할 수 없다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "남의 갤러리")
            galleryService.open(galleryId, photographer.id!!)
            val stranger = userFixture.사용자()

            // when & then
            assertThatThrownBy { galleryService.get(galleryId, stranger.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `없는 갤러리는 조회할 수 없다`() {
            // given
            val photographer = studioFixture.작가()

            // when & then
            assertThatThrownBy { galleryService.get(99999999L, photographer.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("갤러리를 열고 닫을 때")
    inner class OpenAndClose {

        @Test
        fun `작가가 열면 초대된 부부에게 보인다`() {
            // 여는 경로가 없으면 갤러리는 영원히 DRAFT로 남고, 멤버 행이 있어도 부부는 접근이 막힌다.
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "본식")
            val member = galleryFixture.멤버(galleryId)

            // when
            val opened = galleryService.open(galleryId, photographer.id!!)

            // then
            val visible = galleryService.get(galleryId, member.id!!)
            assertSoftly { softly ->
                softly.assertThat(opened.status).isEqualTo(GalleryStatus.OPEN)
                softly.assertThat(visible.title).isEqualTo("본식")
                softly.assertThat(visible.status).isEqualTo(GalleryStatus.OPEN)
            }
        }

        @Test
        fun `담당 작가가 아니면 열 수 없다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "남의 갤러리")
            val other = studioFixture.작가()

            // when & then
            assertThatThrownBy { galleryService.open(galleryId, other.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `이미 열린 갤러리는 다시 열 수 없다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "본식")
            galleryService.open(galleryId, photographer.id!!)

            // when & then
            assertThatThrownBy { galleryService.open(galleryId, photographer.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_STATUS_TRANSITION)
        }

        @Test
        fun `마감해도 부부는 갤러리를 계속 볼 수 있다`() {
            // 마감은 선택을 멈추는 것이지 갤러리를 숨기는 것이 아니다. 마감됐다는 사실 자체를
            // 그 화면에서 알려줘야 한다.
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "본식")
            val member = galleryFixture.멤버(galleryId)
            galleryService.open(galleryId, photographer.id!!)

            // when
            val closed = galleryService.close(galleryId, photographer.id!!)

            // then
            assertThat(closed.status).isEqualTo(GalleryStatus.CLOSED)
            assertThat(galleryService.get(galleryId, member.id!!).status).isEqualTo(GalleryStatus.CLOSED)
        }
    }

    @Nested
    @DisplayName("갤러리를 재오픈할 때")
    inner class Reopen {

        @Test
        fun `재오픈하면 마감 기한을 새로 받는다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = closedGallery(photographer)
            val newDeadline = ZonedDateTime.now().plusDays(30)

            // when
            val result = galleryService.reopen(
                galleryId, photographer.id!!, ReopenGalleryRequest(selectionDeadline = newDeadline),
            )

            // then
            assertThat(result.status).isEqualTo(GalleryStatus.OPEN)
            assertThat(result.selectionDeadline).isNotNull()
        }

        @Test
        fun `이미 지난 기한으로는 재오픈할 수 없다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = closedGallery(photographer)

            // when & then
            assertThatThrownBy {
                galleryService.reopen(
                    galleryId,
                    photographer.id!!,
                    ReopenGalleryRequest(selectionDeadline = ZonedDateTime.now().minusDays(1)),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_SELECTION_DEADLINE)

            // 거절된 재오픈은 상태를 건드리지 않는다.
            assertThat(galleryService.get(galleryId, photographer.id!!).status)
                .isEqualTo(GalleryStatus.CLOSED)
        }

        @Test
        fun `마감된 적 없는 갤러리는 재오픈할 수 없다`() {
            // given
            val photographer = studioFixture.작가()
            val galleryId = createGallery(photographer, "본식")

            // when & then
            assertThatThrownBy {
                galleryService.reopen(galleryId, photographer.id!!, ReopenGalleryRequest())
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_STATUS_TRANSITION)
        }
    }

    @Nested
    @DisplayName("계약 장수를 바꿀 때")
    inner class ChangeMaxSelectablePhotoCount {

        @Test
        fun `계약 장수는 작가만 정한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = null)

            // when & then
            assertThatThrownBy {
                galleryService.changeMaxSelectablePhotoCount(
                    fixture.galleryId, fixture.member.id!!, ChangeMaxSelectablePhotoCountRequest(10),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            val result = galleryService.changeMaxSelectablePhotoCount(
                fixture.galleryId, fixture.photographer.id!!, ChangeMaxSelectablePhotoCountRequest(10),
            )
            assertThat(result.maxSelectablePhotoCount).isEqualTo(10)
        }

        @Test
        fun `계약 장수를 0으로 정할 수 없다`() {
            // 막히는 것은 값을 넣은 작가가 아니라 아무것도 못 고르는 부부다.
            // 컨트롤러의 @Min은 GLOBAL_400_2를 내지만, 서비스 직접 호출은 도메인의
            // 두 번째 방어선(GALLERY_400_3)에 걸린다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = null)

            // when & then
            assertThatThrownBy {
                galleryService.changeMaxSelectablePhotoCount(
                    fixture.galleryId, fixture.photographer.id!!, ChangeMaxSelectablePhotoCountRequest(0),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_MAX_SELECTABLE_PHOTO_COUNT)
        }

        @Test
        fun `계약 장수가 줄어 이미 넘겼다면 남은 장수는 0이다`() {
            // 계약이 줄어드는 일은 실제로 있다. 그때 필요한 것은 작가 쪽의 400이 아니라
            // 부부에게 몇 장이 넘쳤는지 보여주는 화면이다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            photoSelectionService.select(fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds))

            // when
            galleryService.changeMaxSelectablePhotoCount(
                fixture.galleryId, fixture.photographer.id!!, ChangeMaxSelectablePhotoCountRequest(1),
            )

            // then
            val album = photoSelectionService.get(fixture.galleryId, fixture.member.id!!)
            assertSoftly { softly ->
                softly.assertThat(album.maxSelectablePhotoCount).isEqualTo(1)
                softly.assertThat(album.selectedCount).isEqualTo(3)
                softly.assertThat(album.remainingCount).isEqualTo(0)
            }
        }
    }

    @Nested
    @DisplayName("계약 보정 횟수를 바꿀 때")
    inner class ChangeMaxRetouchRoundCount {

        @Test
        fun `보정 횟수는 작가만 정한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()

            // when & then
            assertThatThrownBy {
                galleryService.changeMaxRetouchRoundCount(
                    fixture.galleryId, fixture.member.id!!, ChangeMaxRetouchRoundCountRequest(3),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            val result = galleryService.changeMaxRetouchRoundCount(
                fixture.galleryId, fixture.photographer.id!!, ChangeMaxRetouchRoundCountRequest(3),
            )
            assertThat(result.maxRetouchRoundCount).isEqualTo(3)
        }

        @Test
        fun `보정 횟수를 0으로 정할 수 없다`() {
            // 컨트롤러의 @Min은 GLOBAL 코드를 내지만, 서비스 직접 호출은 도메인의
            // 두 번째 방어선(GALLERY_400_4)에 걸린다. 제한을 없애려면 null을 보낸다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()

            // when & then
            assertThatThrownBy {
                galleryService.changeMaxRetouchRoundCount(
                    fixture.galleryId, fixture.photographer.id!!, ChangeMaxRetouchRoundCountRequest(0),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.INVALID_MAX_RETOUCH_ROUND_COUNT)
        }
    }

    // --- helpers ---

    /** DRAFT 갤러리 하나. 생성 경로 자체가 검증 대상이라 픽스처가 아니라 서비스로 만든다. */
    private fun createGallery(photographer: User, title: String): Long =
        galleryService.create(photographer.id!!, CreateGalleryRequest(title = title)).id

    private fun closedGallery(photographer: User): Long {
        val galleryId = createGallery(photographer, "본식")
        galleryService.open(galleryId, photographer.id!!)
        galleryService.close(galleryId, photographer.id!!)
        return galleryId
    }
}
