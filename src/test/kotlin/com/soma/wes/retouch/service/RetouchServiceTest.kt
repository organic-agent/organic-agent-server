package com.soma.wes.retouch.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 보정 요청 흐름을 서비스 경계에서 확인한다.
 *
 * 보는 것은 셋이다: 회차가 갤러리당 하나씩만 진행되는지, 계약 횟수가 실제로 상한으로
 * 작동하는지, 그리고 제출이 요청 목록을 잠그는지.
 */
@IntegrationTest
class RetouchServiceTest @Autowired constructor(
    private val retouchService: RetouchService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val retouchFixture: RetouchFixture,
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
) {

    @Nested
    @DisplayName("보정사진 페이지를 조회할 때")
    inner class Read {

        @Test
        fun `아무 요청이 없으면 빈 페이지가 온다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 3)

            // when
            val result = retouchService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.maxRetouchRoundCount).isEqualTo(3)
                softly.assertThat(result.remainingRoundCount).isEqualTo(3)
                softly.assertThat(result.rounds).isEmpty()
                softly.assertThat(result.currentRound).isNull()
            }
        }

        @Test
        fun `담긴 사진과 남은 횟수를 함께 돌려준다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            add(fixture, photoIds.take(2))

            // when
            val result = retouchService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.remainingRoundCount).isEqualTo(3)
                softly.assertThat(result.rounds).hasSize(1)
                softly.assertThat(result.rounds[0].status).isEqualTo(RetouchRoundStatus.DRAFTING)
                softly.assertThat(result.rounds[0].photoCount).isEqualTo(2L)
                softly.assertThat(result.currentRound!!.roundNo).isEqualTo(1)
                softly.assertThat(result.currentRound!!.photos).hasSize(2)
                softly.assertThat(result.currentRound!!.photos[0].photo.viewUrl).contains("X-Amz-Signature")
            }
        }

        @Test
        fun `작가도 요청 목록을 본다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)

            // when
            val result = retouchService.get(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertThat(result.currentRound!!.photos).hasSize(1)
        }
    }

    @Nested
    @DisplayName("보정사진을 담을 때")
    inner class AddPhotos {

        @Test
        fun `첫 담기에 DRAFTING 회차가 만들어진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            val result = add(fixture, photoIds)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.currentRound!!.roundNo).isEqualTo(1)
                softly.assertThat(result.currentRound!!.status).isEqualTo(RetouchRoundStatus.DRAFTING)
                softly.assertThat(result.currentRound!!.photos).hasSize(2)
            }
            assertThat(retouchRoundRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `이번 회차에 이미 담긴 사진이 섞이면 한 장도 담기지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            add(fixture, photoIds.take(2))

            // when & then
            assertThatThrownBy { add(fixture, photoIds.drop(1)) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_ALREADY_IN_ROUND)

            // 부분 성공이 없다는 것은 응답만으로는 확인되지 않는다.
            assertThat(retouchPhotoRepository.count()).isEqualTo(2L)
        }

        @Test
        fun `업로드가 끝나지 않은 사진은 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.대기중_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy { add(fixture, photoIds) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_UPLOADED)
        }

        @Test
        fun `다른 갤러리의 사진은 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = galleryFixture.멤버와_열린_갤러리()
            val otherPhotoIds = photoFixture.업로드된_사진(other.galleryId, count = 1)

            // when & then
            assertThatThrownBy { add(fixture, otherPhotoIds) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `이전 회차가 응답 대기 중이면 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            retouchFixture.제출된_회차(fixture.galleryId, photoIds = photoIds.take(1))

            // when & then
            assertThatThrownBy { add(fixture, photoIds.drop(1)) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.ROUND_IN_PROGRESS)
        }

        @Test
        fun `이전 회차가 끝났으면 같은 사진도 다음 회차에 다시 담긴다`() {
            // 1회차 결과가 미흡했던 컷을 2회차에 다시 넣는 재보정 흐름이다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds)

            // when
            val result = add(fixture, photoIds)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.currentRound!!.roundNo).isEqualTo(2)
                softly.assertThat(result.currentRound!!.photos).hasSize(1)
                softly.assertThat(result.rounds).hasSize(2)
            }
        }

        @Test
        fun `계약 횟수를 다 썼으면 새 회차가 열리지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 1)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds)

            // when & then
            assertThatThrownBy { add(fixture, photoIds) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }

        @Test
        fun `작가는 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                retouchService.addPhotos(
                    fixture.galleryId, fixture.photographer.id!!, AddRetouchPhotosRequest(photoIds),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `마감이 지난 갤러리에는 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThatThrownBy { add(fixture, photoIds) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }
    }

    @Nested
    @DisplayName("보정사진을 뺄 때")
    inner class RemovePhoto {

        @Test
        fun `회차에서 빠진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            add(fixture, photoIds)

            // when
            retouchService.removePhoto(fixture.galleryId, photoIds[0], fixture.member.id!!)

            // then
            assertThat(retouchPhotoRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `회차에 없는 사진이면 404다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)

            // when & then
            assertThatThrownBy {
                retouchService.removePhoto(fixture.galleryId, photoIds[0], fixture.member.id!!)
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
    }

    @Nested
    @DisplayName("보정 요청을 작성할 때")
    inner class UpdatePhoto {

        @Test
        fun `요청 텍스트와 주석 key가 저장된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)
            val issued = retouchService.issueAnnotationUploadUrl(fixture.galleryId, fixture.member.id!!)

            // when
            val result = retouchService.updatePhoto(
                fixture.galleryId,
                photoIds[0],
                fixture.member.id!!,
                UpdateRetouchPhotoRequest(requestText = "잡티 제거", annotationKey = issued.annotationKey),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.requestText).isEqualTo("잡티 제거")
                softly.assertThat(result.annotationUrl).contains("X-Amz-Signature")
                softly.assertThat(result.hasResult).isFalse()
            }
        }

        @Test
        fun `이 갤러리의 주석 key가 아니면 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.updatePhoto(
                    fixture.galleryId,
                    photoIds[0],
                    fixture.member.id!!,
                    UpdateRetouchPhotoRequest(annotationKey = "galleries/999/retouch/annotations/a.png"),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ANNOTATION_KEY)
        }

        @Test
        fun `회차에 없는 사진이면 404다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            add(fixture, photoIds.take(1))

            // when & then
            assertThatThrownBy {
                retouchService.updatePhoto(
                    fixture.galleryId, photoIds[1], fixture.member.id!!, UpdateRetouchPhotoRequest(),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }

        @Test
        fun `제출한 뒤에는 적을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)
            retouchService.submitRound(fixture.galleryId, fixture.member.id!!)

            // when & then
            assertThatThrownBy {
                retouchService.updatePhoto(
                    fixture.galleryId, photoIds[0], fixture.member.id!!, UpdateRetouchPhotoRequest(),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
    }

    @Nested
    @DisplayName("주석 업로드 URL을 발급할 때")
    inner class IssueAnnotationUploadUrl {

        @Test
        fun `이 갤러리의 주석 경로 key와 서명 URL이 온다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()

            // when
            val result = retouchService.issueAnnotationUploadUrl(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.annotationKey)
                    .startsWith("galleries/${fixture.galleryId}/retouch/annotations/")
                    .endsWith(".png")
                softly.assertThat(result.uploadUrl).contains("X-Amz-Signature")
                softly.assertThat(result.uploadUrlTtlSeconds).isPositive()
            }
        }
    }

    @Nested
    @DisplayName("회차를 제출할 때")
    inner class SubmitRound {

        @Test
        fun `DRAFTING 회차가 REQUESTED가 되고 횟수를 쓴다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 2)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            add(fixture, photoIds)

            // when
            val result = retouchService.submitRound(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.currentRound!!.status).isEqualTo(RetouchRoundStatus.REQUESTED)
                softly.assertThat(result.currentRound!!.requestedAt).isNotNull()
                softly.assertThat(result.remainingRoundCount).isEqualTo(1)
            }
        }

        @Test
        fun `아무것도 담지 않았으면 제출할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()

            // when & then
            assertThatThrownBy { retouchService.submitRound(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.EMPTY_ROUND)
        }

        @Test
        fun `제출 직후 다시 제출하면 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)
            retouchService.submitRound(fixture.galleryId, fixture.member.id!!)

            // when & then
            assertThatThrownBy { retouchService.submitRound(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.EMPTY_ROUND)
        }

        @Test
        fun `회차를 만든 뒤 계약 횟수가 줄었으면 제출이 막힌다`() {
            // 담기 시점 검사를 통과했어도 제출이 최종 관문이다.
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds.take(1))
            add(fixture, photoIds.drop(1))
            galleryFixture.보정_횟수_변경(fixture.galleryId, maxRetouchRoundCount = 1)

            // when & then
            assertThatThrownBy { retouchService.submitRound(fixture.galleryId, fixture.member.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }
    }

    private fun add(fixture: OpenGallery, photoIds: List<Long>) =
        retouchService.addPhotos(fixture.galleryId, fixture.member.id!!, AddRetouchPhotosRequest(photoIds))
}
