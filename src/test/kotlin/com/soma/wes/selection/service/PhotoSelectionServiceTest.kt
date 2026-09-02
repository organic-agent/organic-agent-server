package com.soma.wes.selection.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.selection.domain.PhotoSelectionStatus
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 최종 선택 앨범을 서비스 경계에서 확인한다.
 *
 * 보는 것은 셋이다: 계약 장수가 실제로 상한으로 작동하는지, 제출이 목록을 잠그는지,
 * 그리고 누가 무엇을 할 수 있는지(고르는 것은 부부, 되돌리는 것은 작가).
 */
@IntegrationTest
class PhotoSelectionServiceTest @Autowired constructor(
    private val photoSelectionService: PhotoSelectionService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val retouchFixture: RetouchFixture,
    private val photoRepository: PhotoRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val galleryRepository: GalleryRepository,
) {

    @Nested
    @DisplayName("앨범을 조회할 때")
    inner class Read {

        @Test
        fun `고른 사진과 남은 장수를 함께 돌려준다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds.take(2))

            // when
            val result = photoSelectionService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(PhotoSelectionStatus.SELECTING)
                softly.assertThat(result.maxSelectablePhotoCount).isEqualTo(3)
                softly.assertThat(result.selectedCount).isEqualTo(2)
                softly.assertThat(result.remainingCount).isEqualTo(1)
                softly.assertThat(result.photos).hasSize(2)
                softly.assertThat(result.photos[0].photo.viewUrl).contains("X-Amz-Signature")
                softly.assertThat(result.photos[0].retouchPhotoId).isNull()
            }
        }

        @Test
        fun `아직 아무것도 고르지 않았으면 빈 앨범이 온다`() {
            // 조회가 앨범 행을 만들지 않는다. 만들면 갤러리를 열어보기만 한 사람 수만큼 빈 앨범이 쌓인다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 50)

            // when
            val result = photoSelectionService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.status).isEqualTo(PhotoSelectionStatus.SELECTING)
                softly.assertThat(result.selectedCount).isEqualTo(0)
                softly.assertThat(result.remainingCount).isEqualTo(50)
                softly.assertThat(result.photos).isEmpty()
            }
            assertThat(photoSelectionRepository.count()).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("사진을 담을 때")
    inner class AddPhotos {

        @Test
        fun `정확히 계약 장수만큼은 담긴다`() {
            // 경계값이 막히면 부부는 마지막 한 장을 영영 담지 못한다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)

            // when
            val result = photoSelectionService.select(
                fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.selectedCount).isEqualTo(3)
                softly.assertThat(result.remainingCount).isEqualTo(0)
            }
        }

        @Test
        fun `계약 장수를 넘기면 한 장도 담기지 않는다`() {
            // 들어갈 수 있는 만큼만 담고 나머지를 버리면 화면에는 성공으로 보이고,
            // 어느 사진이 빠졌는지는 아무도 모른다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 2)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds.take(1))

            // when & then
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds.drop(1)),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED)

            // 부분 성공이 없다는 것은 응답만으로는 확인되지 않는다.
            assertThat(photoSelectionItemRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `계약 장수가 없으면 제한 없이 담는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = null)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds)

            // when
            val result = photoSelectionService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.maxSelectablePhotoCount).isNull()
                softly.assertThat(result.remainingCount).isNull()
                softly.assertThat(result.selectedCount).isEqualTo(3)
            }
        }

        @Test
        fun `이미 담긴 사진은 다시 담을 수 없다`() {
            // 신랑과 신부가 각자의 화면에서 고르므로, 겹쳤다는 것은 보고 있는 화면이 낡았다는 뜻이다.
            // 조용히 건너뛰면 신부는 자기가 방금 담았다고 생각한다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds.take(2))

            // when & then
            // 한 장만 겹쳐도 통째로 막는다. 겹친 것만 빼고 담으면 화면과 실제가 더 벌어진다.
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds.drop(1)),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_ALREADY_SELECTED)

            assertThat(photoSelectionItemRepository.count()).isEqualTo(2L)
        }

        @Test
        fun `업로드가 끝나지 않은 사진은 고를 수 없다`() {
            // 실체가 없는 사진이 납품 목록에 섞이면, 작가는 목록에는 있는데 열리지 않는 항목을 받는다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val pendingIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(pendingIds),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_NOT_UPLOADED)
        }

        @Test
        fun `다른 갤러리의 사진은 고를 수 없다`() {
            // 갤러리 권한만 보고 사진 id를 믿으면, 자기 앨범으로 남의 사진을 끌어와 서명 URL까지 받아낸다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val otherFixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val otherPhotoIds = photoFixture.업로드된_사진(otherFixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(otherPhotoIds),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_NOT_IN_GALLERY)
        }
    }

    @Nested
    @DisplayName("사진을 뺄 때")
    inner class RemovePhotos {

        @Test
        fun `한 장을 빼면 앨범에서만 빠진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            select(fixture, photoIds)

            // when
            photoSelectionService.deselectPhoto(fixture.galleryId, photoIds.first(), fixture.member.id!!)

            // then
            assertThat(photoSelectionItemRepository.count()).isEqualTo(1L)
            assertThat(photoRepository.countByGalleryId(fixture.galleryId)).isEqualTo(2L)
        }

        @Test
        fun `앨범에 없는 사진을 한 장 빼면 실패한다`() {
            // 조용히 성공시키면 프론트는 지운 줄 알고 화면에서 지운다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            select(fixture, photoIds.take(1))

            // when & then
            assertThatThrownBy {
                photoSelectionService.deselectPhoto(fixture.galleryId, photoIds.last(), fixture.member.id!!)
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_NOT_SELECTED)
        }

        @Test
        fun `여러 장을 뺄 때는 이미 빠진 사진이 섞여 있어도 막지 않는다`() {
            // 화면이 조금 낡은 것뿐이라 통째로 거절하면 사용자는 어느 것이 문제인지 모른 채 다시 골라야 한다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 5)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds.take(2))

            // when
            val result = photoSelectionService.deselect(
                fixture.galleryId, fixture.member.id!!, DeselectPhotosRequest(photoIds),
            )

            // then
            assertThat(result.selectedCount).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("제출하고 되돌릴 때")
    inner class SubmitAndWithdraw {

        @Test
        fun `제출하면 목록이 잠기고 작가가 되돌리면 다시 열린다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            select(fixture, photoIds.take(2))

            // when & then
            val submitted = photoSelectionService.submit(fixture.galleryId, fixture.member.id!!)
            assertSoftly { softly ->
                softly.assertThat(submitted.status).isEqualTo(PhotoSelectionStatus.SUBMITTED)
                softly.assertThat(galleryRepository.findById(fixture.galleryId).orElseThrow().stage)
                    .isEqualTo(GalleryStage.SELECTION_COMPLETED)
                // 계약 장수에 못 미쳐도 제출된다. 화면은 목표와 현재 장수를 보고 미리 물어본다.
                softly.assertThat(submitted.selectedCount).isEqualTo(2)
                softly.assertThat(submitted.submittedAt).isNotNull()
            }

            // 작가가 이 목록을 보고 보정에 들어가므로 그 뒤에 조용히 바뀌면 안 된다.
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds.drop(2)),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
            assertThatThrownBy {
                photoSelectionService.deselectPhoto(fixture.galleryId, photoIds.first(), fixture.member.id!!)
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)

            val withdrawn = photoSelectionService.withdraw(fixture.galleryId, fixture.photographer.id!!)
            assertSoftly { softly ->
                softly.assertThat(withdrawn.status).isEqualTo(PhotoSelectionStatus.SELECTING)
                softly.assertThat(withdrawn.submittedAt).isNull()
                softly.assertThat(galleryRepository.findById(fixture.galleryId).orElseThrow().stage)
                    .isEqualTo(GalleryStage.SELECTION_IN_PROGRESS)
            }

            select(fixture, photoIds.drop(2))
        }

        @Test
        fun `부부는 제출을 되돌릴 수 없다`() {
            // 부부가 스스로 되돌릴 수 있으면 제출이라는 잠금이 아무것도 잠그지 않는다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            select(fixture, photoFixture.업로드된_사진(fixture.galleryId, count = 1))
            photoSelectionService.submit(fixture.galleryId, fixture.member.id!!)

            // when & then
            assertThatThrownBy { photoSelectionService.withdraw(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `제출되지 않은 앨범은 되돌릴 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            select(fixture, photoFixture.업로드된_사진(fixture.galleryId, count = 1))

            // when & then
            assertThatThrownBy { photoSelectionService.withdraw(fixture.galleryId, fixture.photographer.id!!) }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_NOT_SUBMITTED)
        }

        @Test
        fun `한 장도 고르지 않으면 제출할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)

            // when & then
            assertThatThrownBy { photoSelectionService.submit(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.EMPTY_SELECTION)
        }
    }

    @Nested
    @DisplayName("권한을 확인할 때")
    inner class Authorization {

        @Test
        fun `작가는 고를 수 없고 보기만 한다`() {
            // 작가가 고객 대신 고르면 이 제품이 하는 일이 사라진다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when & then
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.photographer.id!!, SelectPhotosRequest(photoIds),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            val result = photoSelectionService.get(fixture.galleryId, fixture.photographer.id!!)
            assertThat(result.selectedCount).isEqualTo(0)
        }

        @Test
        fun `마감이 지나면 부부는 고를 수 없고 제출 결과는 계속 보인다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            select(fixture, photoIds)
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThatThrownBy {
                photoSelectionService.deselectPhoto(fixture.galleryId, photoIds.first(), fixture.member.id!!)
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)

            // 조회는 requireViewer 기준이라 마감 뒤에도 양쪽 모두 열린다 — 마감됐다는 사실 자체를
            // 그 화면에서 알려줘야 하고, 작가는 결과를 보고 보정에 들어간다.
            assertThat(photoSelectionService.get(fixture.galleryId, fixture.member.id!!).selectedCount)
                .isEqualTo(2)
            assertThat(photoSelectionService.get(fixture.galleryId, fixture.photographer.id!!).selectedCount)
                .isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("보정본을 담을 때")
    inner class SelectRetouched {

        @Test
        fun `항목은 원본을 가리키고 결과 URL이 함께 온다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val retouched = retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, photoIds = photoIds.take(1))

            // when — 원본 한 장과 보정본 한 장을 함께 담는다
            val result = photoSelectionService.select(
                fixture.galleryId, fixture.member.id!!,
                SelectPhotosRequest(
                    photoIds = photoIds.drop(1),
                    retouchPhotos = listOf(
                        SelectPhotosRequest.RetouchPhotoRequest(
                            photoId = photoIds[0],
                            retouchPhotoId = retouched.single().requiredId,
                        ),
                    ),
                ),
            )

            // then
            val byPhotoId = result.photos.associateBy { it.photo.photoId }
            assertSoftly { softly ->
                softly.assertThat(result.selectedCount).isEqualTo(2)
                softly.assertThat(byPhotoId.getValue(photoIds[0]).retouchPhotoId)
                    .isEqualTo(retouched.single().requiredId)
                softly.assertThat(byPhotoId.getValue(photoIds[0]).resultUrl).contains("X-Amz-Signature")
                softly.assertThat(byPhotoId.getValue(photoIds[1]).retouchPhotoId).isNull()
                softly.assertThat(byPhotoId.getValue(photoIds[1]).resultUrl).isNull()
            }
            assertThat(photoSelectionItemRepository.findAll().single { it.photoId == photoIds[0] }.retouchPhotoId)
                .isEqualTo(retouched.single().requiredId)
        }

        @Test
        fun `같은 컷을 원본과 보정본으로 함께 담을 수 없다`() {
            // 항목의 photoId는 항상 원본이라, 둘은 같은 한 자리를 두고 겹친다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val retouched = retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, photoIds = photoIds)

            // when & then
            assertThatThrownBy {
                photoSelectionService.select(
                    fixture.galleryId, fixture.member.id!!,
                    SelectPhotosRequest(
                        photoIds = photoIds,
                        retouchPhotos = listOf(
                            SelectPhotosRequest.RetouchPhotoRequest(
                                photoId = photoIds[0],
                                retouchPhotoId = retouched.single().requiredId,
                            ),
                        ),
                    ),
                )
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
        }

        @Test
        fun `이미 원본으로 담긴 컷은 보정본으로도 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val retouched = retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, photoIds = photoIds)
            select(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                selectRetouched(fixture, photoIds[0], retouched.single().requiredId)
            }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.PHOTO_ALREADY_SELECTED)
        }

        @Test
        fun `원본이 다른 보정 항목은 담을 수 없다`() {
            // 짝이 어긋난 채 저장되면 앨범에는 A컷이 담겼는데 화면에는 B컷의 보정본이 걸린다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val retouched = retouchFixture.결과와_함께_완료된_회차(fixture.galleryId, photoIds = photoIds.take(1))

            // when & then
            assertThatThrownBy {
                selectRetouched(fixture, photoIds[1], retouched.single().requiredId)
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.RETOUCH_PHOTO_MISMATCH)
        }

        @Test
        fun `아직 결과가 없는 보정 항목은 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val requested = retouchFixture.결과_없는_제출된_회차(fixture.galleryId, photoIds = photoIds)

            // when & then
            assertThatThrownBy {
                selectRetouched(fixture, photoIds[0], requested.single().requiredId)
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.RESULT_NOT_UPLOADED)
        }

        @Test
        fun `다른 갤러리의 보정 항목은 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val otherFixture = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoIds = photoFixture.업로드된_사진(otherFixture.galleryId, count = 1)
            val otherRetouched =
                retouchFixture.결과와_함께_완료된_회차(otherFixture.galleryId, photoIds = otherPhotoIds)

            // when & then
            assertThatThrownBy {
                selectRetouched(fixture, photoIds[0], otherRetouched.single().requiredId)
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.RETOUCH_PHOTO_NOT_IN_GALLERY)
        }
    }

    // --- helpers ---

    private fun select(fixture: OpenGallery, photoIds: List<Long>) {
        photoSelectionService.select(fixture.galleryId, fixture.member.id!!, SelectPhotosRequest(photoIds))
    }

    private fun selectRetouched(fixture: OpenGallery, photoId: Long, retouchPhotoId: Long) {
        photoSelectionService.select(
            fixture.galleryId, fixture.member.id!!,
            SelectPhotosRequest(
                retouchPhotos = listOf(
                    SelectPhotosRequest.RetouchPhotoRequest(photoId = photoId, retouchPhotoId = retouchPhotoId),
                ),
            ),
        )
    }
}
