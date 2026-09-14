package com.soma.wes.retouch.service

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.service.PhotoRatingService
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.retouch.domain.RetouchPoint
import com.soma.wes.retouch.domain.RetouchRoundStatus
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.CompleteResultsRequest
import com.soma.wes.retouch.dto.request.RetouchRequestItem
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.retouch.dto.request.IssueResultUploadUrlsRequest
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.fixture.RetouchFixture
import com.soma.wes.retouch.repository.RetouchPhotoRepository
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.retouch.support.RetouchStorageTestConfig
import com.soma.wes.retouch.support.RetouchTestStorage
import com.soma.wes.retouch.dto.request.MatchRetouchResultsRequest
import org.springframework.context.annotation.Import
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 보정 요청 흐름을 서비스 경계에서 확인한다.
 *
 * 보는 것은 넷이다: 회차가 갤러리당 하나씩만 진행되는지, 계약 횟수가 실제로 상한으로
 * 작동하는지, 제출이 요청 목록을 잠그는지, 그리고 전 항목의 결과가 회차를 끝내는 관문인지.
 */
@IntegrationTest
@Import(RetouchStorageTestConfig::class)
class RetouchServiceTest @Autowired constructor(
    private val retouchService: RetouchService,
    private val selectionService: PhotoSelectionService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val retouchFixture: RetouchFixture,
    private val retouchRoundRepository: RetouchRoundRepository,
    private val retouchPhotoRepository: RetouchPhotoRepository,
    private val jdbcClient: JdbcClient,
    private val galleryRepository: GalleryRepository,
    private val testStorage: RetouchTestStorage,
    private val ratingService: PhotoRatingService,
    private val activity: com.soma.wes.activity.repository.ActivityRepository,
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
        fun `작가는 제출 전 초안 사진을 볼 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)

            // when
            val result = retouchService.get(fixture.galleryId, fixture.photographer.id!!)

            // then
            assertThat(result.currentRound!!.photos).isEmpty()
        }

        @Test
        fun `관리자가 소프트 삭제한 항목은 사용자 조회에서 사라진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            add(fixture, listOf(photoId))
            val round = retouchRoundRepository.findByGalleryIdAndStatus(
                fixture.galleryId,
                RetouchRoundStatus.DRAFTING,
            )!!

            val deletedCount = jdbcClient.sql(
                """
                UPDATE retouch_photos
                SET deleted_at = CURRENT_TIMESTAMP,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE round_id = :roundId
                  AND photo_id = :photoId
                """.trimIndent(),
            )
                .param("roundId", round.requiredId)
                .param("photoId", photoId)
                .update()
            assertThat(deletedCount).isEqualTo(1)

            // when
            val result = retouchService.get(fixture.galleryId, fixture.member.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.rounds.single().photoCount).isZero()
                softly.assertThat(result.currentRound!!.photos).isEmpty()
                softly.assertThat(retouchPhotoRepository.findByRoundIdAndPhotoId(round.requiredId, photoId)).isNull()
            }
            val rawRowStillRestorable = jdbcClient.sql(
                """
                SELECT deleted_at IS NOT NULL
                FROM retouch_photos
                WHERE round_id = :roundId
                  AND photo_id = :photoId
                """.trimIndent(),
            )
                .param("roundId", round.requiredId)
                .param("photoId", photoId)
                .query(Boolean::class.java)
                .single()
            assertThat(rawRowStillRestorable).isTrue()
        }
    }

    @Nested
    @DisplayName("초안 회차에 담고 요청을 적을 때")
    inner class DraftRound {

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
                softly.assertThat(result.currentRound!!.photos).hasSize(2)
                softly.assertThat(result.rounds.single().status).isEqualTo(RetouchRoundStatus.DRAFTING)
            }
        }

        @Test
        fun `이번 회차에 이미 담긴 사진이 섞이면 한 장도 담기지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            add(fixture, photoIds.take(1))

            // when & then
            assertThatThrownBy { add(fixture, photoIds) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_ALREADY_IN_ROUND)
            assertThat(retouchService.get(fixture.galleryId, fixture.member.requiredId).currentRound!!.photos).hasSize(1)
        }

        @Test
        fun `이전 회차가 응답 대기 중이면 담을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy { add(fixture, photoIds) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.ROUND_IN_PROGRESS)
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
                    fixture.galleryId, fixture.photographer.requiredId, AddRetouchPhotosRequest(photoIds),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `요청 텍스트와 포인트가 저장된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)

            // when
            val result = retouchService.updatePhoto(
                fixture.galleryId,
                photoIds[0],
                fixture.member.requiredId,
                UpdateRetouchPhotoRequest(
                    requestText = "잡티 제거",
                    points = listOf(RetouchPoint(x = 0.4, y = 0.5, text = "여기 지워주세요")),
                ),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.requestText).isEqualTo("잡티 제거")
                softly.assertThat(result.points.single().text).isEqualTo("여기 지워주세요")
                softly.assertThat(result.hasResult).isFalse()
            }
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
                    fixture.galleryId, photoIds[1], fixture.member.requiredId, UpdateRetouchPhotoRequest(),
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
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.updatePhoto(
                    fixture.galleryId, photoIds[0], fixture.member.requiredId, UpdateRetouchPhotoRequest(),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }
    }

    @Nested
    @DisplayName("요청을 일괄 제출할 때")
    inner class SubmitRequests {

        @Test
        fun `셀렉 제출이 1회차를 REQUESTED로 만들고 요청 문장을 담는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 2)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)

            // when
            제출까지(fixture, photoIds, listOf(RetouchRequestItem(photoIds[0], requestText = "밝게 해주세요")))

            // then
            val result = retouchService.get(fixture.galleryId, fixture.member.requiredId)
            assertSoftly { softly ->
                softly.assertThat(result.rounds.single().status).isEqualTo(RetouchRoundStatus.REQUESTED)
                softly.assertThat(result.remainingRoundCount).isEqualTo(1)
                softly.assertThat(result.currentRound!!.photos).hasSize(2)
                softly.assertThat(
                    result.currentRound!!.photos.first { it.photo.photoId == photoIds[0] }.requestText,
                ).isEqualTo("밝게 해주세요")
            }
        }

        @Test
        fun `선택 목록에 없는 사진의 요청은 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            selectionService.select(
                fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoIds[0])),
            )

            // when & then
            assertThatThrownBy {
                selectionService.submit(
                    fixture.galleryId,
                    fixture.member.requiredId,
                    SubmitRetouchRequestsRequest(listOf(RetouchRequestItem(photoIds[1], requestText = "이 컷도"))),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_SELECTED)
        }

        @Test
        fun `같은 사진이 두 번 들어오면 한 건도 적히지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            selectionService.select(
                fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoId)),
            )

            // when & then
            assertThatThrownBy {
                selectionService.submit(
                    fixture.galleryId,
                    fixture.member.requiredId,
                    SubmitRetouchRequestsRequest(
                        listOf(
                            RetouchRequestItem(photoId, requestText = "밝게"),
                            RetouchRequestItem(photoId, requestText = "어둡게"),
                        ),
                    ),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_ALREADY_IN_ROUND)
            assertThat(retouchRoundRepository.findFirstByGalleryIdOrderByRoundNoDesc(fixture.galleryId)).isNull()
        }

        @Test
        fun `이전 회차가 응답 대기 중이면 다음 회차를 제출할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 3)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.submitRequests(
                    fixture.galleryId, 2, fixture.member.requiredId, SubmitRetouchRequestsRequest(),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ROUND_STATUS)
        }

        @Test
        fun `계약 횟수를 다 썼으면 다음 회차를 제출할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리(maxRetouchRoundCount = 1)
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds)
            selectionService.select(
                fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = photoIds),
            )
            selectionService.submit(fixture.galleryId, fixture.member.requiredId)

            // when & then
            assertThatThrownBy {
                retouchService.submitRequests(
                    fixture.galleryId, 2, fixture.member.requiredId, SubmitRetouchRequestsRequest(),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.MAX_RETOUCH_ROUND_COUNT_EXCEEDED)
        }

        @Test
        fun `작가는 요청을 제출할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.submitRequests(
                    fixture.galleryId, 2, fixture.photographer.requiredId, SubmitRetouchRequestsRequest(),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Nested
    @DisplayName("회차 상세를 조회할 때")
    inner class GetRound {

        @Test
        fun `보내기 전에는 클라이언트에게 결과 URL을 숨긴다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, photoIds, listOf(RetouchRequestItem(photoIds[0], requestText = "밝게 해주세요")))
            결과_확정(fixture, roundNo = 1, photoIds = photoIds.take(1))

            // when
            val result = retouchService.getRound(fixture.galleryId, 1, fixture.member.id!!)

            // then
            val byPhotoId = result.photos.associateBy { it.photo.photoId }
            assertSoftly { softly ->
                softly.assertThat(result.roundNo).isEqualTo(1)
                softly.assertThat(result.status).isEqualTo(RetouchRoundStatus.REQUESTED)
                softly.assertThat(result.photos).hasSize(2)
                softly.assertThat(byPhotoId.getValue(photoIds[0]).requestText).isEqualTo("밝게 해주세요")
                softly.assertThat(byPhotoId.getValue(photoIds[0]).photo.viewUrl).contains("X-Amz-Signature")
                softly.assertThat(byPhotoId.getValue(photoIds[0]).resultUrl).isNull()
                softly.assertThat(byPhotoId.getValue(photoIds[1]).resultUrl).isNull()
            }
        }

        @Test
        fun `없는 회차면 404다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()

            // when & then
            assertThatThrownBy { retouchService.getRound(fixture.galleryId, 1, fixture.member.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.ROUND_NOT_FOUND)
        }
    }

    @Nested
    @DisplayName("결과 업로드 URL을 발급할 때")
    inner class IssueResultUploadUrls {

        @Test
        fun `회차 결과 경로의 key와 서명 URL이 항목별로 온다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, photoIds)

            // when
            val result = retouchService.issueResultUploadUrls(
                fixture.galleryId, 1, fixture.photographer.id!!, 발급_요청(photoIds),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.uploads).hasSize(2)
                softly.assertThat(result.uploads.map { it.photoId }).isEqualTo(photoIds)
                result.uploads.forEach {
                    softly.assertThat(it.resultKey)
                        .startsWith("galleries/${fixture.galleryId}/retouch/results/1/")
                    softly.assertThat(it.uploadUrl).contains("X-Amz-Signature")
                }
                softly.assertThat(result.uploadUrlTtlSeconds).isGreaterThan(0L)
            }
        }

        @Test
        fun `부부는 발급할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.issueResultUploadUrls(
                    fixture.galleryId, 1, fixture.member.id!!, 발급_요청(photoIds),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `제출 전 회차에는 발급할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            add(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.issueResultUploadUrls(
                    fixture.galleryId, 1, fixture.photographer.id!!, 발급_요청(photoIds),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ROUND_STATUS)
        }

        @Test
        fun `회차에 없는 사진이 섞이면 통째로 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, photoIds.take(1))

            // when & then
            assertThatThrownBy {
                retouchService.issueResultUploadUrls(
                    fixture.galleryId, 1, fixture.photographer.id!!, 발급_요청(photoIds),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.PHOTO_NOT_IN_ROUND)
        }

        @Test
        fun `지원하지 않는 형식은 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.issueResultUploadUrls(
                    fixture.galleryId, 1, fixture.photographer.id!!,
                    발급_요청(photoIds, contentType = "application/pdf"),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.UNSUPPORTED_CONTENT_TYPE)
        }
    }

    @Nested
    @DisplayName("결과 업로드를 확정할 때")
    inner class CompleteResults {

        @Test
        fun `항목에 결과가 기록되고 다시 확정하면 덮어쓴다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)
            결과_확정(fixture, roundNo = 1, photoIds = photoIds)

            // when
            val newKey = "galleries/${fixture.galleryId}/retouch/results/1/retry.png"
            jdbcClient.sql("update gallery_activity set last_activity_at = timestamp with time zone '2000-01-01 00:00:00Z'").update()
            val result = retouchService.completeResults(
                fixture.galleryId, 1, fixture.photographer.id!!,
                CompleteResultsRequest(
                    listOf(
                        CompleteResultsRequest.ResultRequest(
                            photoId = photoIds[0],
                            resultKey = newKey,
                            contentType = "image/png",
                        ),
                    ),
                ),
            )

            // then
            val round = retouchRoundRepository.findByGalleryIdAndRoundNo(fixture.galleryId, 1)!!
            val item = retouchPhotoRepository.findAllByRoundId(round.requiredId).single()
            assertSoftly { softly ->
                softly.assertThat(result.photos.single().resultUrl).contains("X-Amz-Signature")
                softly.assertThat(item.resultKey).isEqualTo(newKey)
                softly.assertThat(item.resultContentType).isEqualTo("image/png")
                softly.assertThat(activity.findGalleryActivity(listOf(fixture.galleryId)).getValue(fixture.galleryId).toInstant())
                    .isAfter(java.time.Instant.parse("2000-01-01T00:00:00Z"))
            }
        }

        @Test
        fun `이 회차의 결과 경로가 아닌 key는 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.completeResults(
                    fixture.galleryId, 1, fixture.photographer.id!!,
                    CompleteResultsRequest(
                        listOf(
                            CompleteResultsRequest.ResultRequest(
                                photoId = photoIds[0],
                                resultKey = "galleries/${fixture.galleryId}/retouch/results/2/other.jpg",
                                contentType = "image/jpeg",
                            ),
                        ),
                    ),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_RESULT_KEY)
        }

        @Test
        fun `끝난 회차에는 확정할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds)

            // when & then
            assertThatThrownBy {
                retouchService.completeResults(
                    fixture.galleryId, 1, fixture.photographer.id!!,
                    CompleteResultsRequest(
                        listOf(
                            CompleteResultsRequest.ResultRequest(
                                photoId = photoIds[0],
                                resultKey = "galleries/${fixture.galleryId}/retouch/results/1/late.jpg",
                                contentType = "image/jpeg",
                            ),
                        ),
                    ),
                )
            }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
    }

    @Nested
    @DisplayName("회차를 완료할 때")
    inner class CompleteRound {

        @Test
        fun `전 항목에 결과가 있으면 회차가 끝난다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, photoIds)
            결과_확정(fixture, roundNo = 1, photoIds = photoIds)

            // when
            val result = retouchService.completeRound(fixture.galleryId, 1, fixture.photographer.id!!)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.rounds.single().status).isEqualTo(RetouchRoundStatus.COMPLETED)
                softly.assertThat(result.rounds.single().completedAt).isNotNull()
                softly.assertThat(result.currentRound).isNull()
                softly.assertThat(galleryRepository.findById(fixture.galleryId).orElseThrow().stage)
                    .isEqualTo(GalleryStage.DELIVERY)
            }
        }

        @Test
        fun `결과가 없는 항목이 있으면 끝낼 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, photoIds)
            결과_확정(fixture, roundNo = 1, photoIds = photoIds.take(1))

            // when & then
            assertThatThrownBy { retouchService.completeRound(fixture.galleryId, 1, fixture.photographer.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.MISSING_RESULT)
        }

        @Test
        fun `부부는 완료할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, photoIds)
            결과_확정(fixture, roundNo = 1, photoIds = photoIds)

            // when & then
            assertThatThrownBy { retouchService.completeRound(fixture.galleryId, 1, fixture.member.id!!) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `끝난 회차를 다시 완료하면 거절된다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            retouchFixture.완료된_회차(fixture.galleryId, photoIds = photoIds)

            // when & then
            assertThatThrownBy { retouchService.completeRound(fixture.galleryId, 1, fixture.photographer.id!!) }
                .isInstanceOf(RetouchException::class.java)
                .extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
    }

    @Nested
    @DisplayName("보정본 전달과 확정의 공개 경계를 검증할 때")
    inner class DeliveryBoundary {
        @Test
        fun `작가의 보정 응답은 제출 전 사진과 클라이언트 별점을 노출하지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            ratingService.rate(fixture.galleryId, ids.single(), fixture.member.requiredId, RatePhotoRequest(score = 5))
            add(fixture, ids)
            assertThat(retouchService.get(fixture.galleryId, fixture.photographer.requiredId).currentRound!!.photos).isEmpty()
            assertThat(retouchService.getRound(fixture.galleryId, 1, fixture.photographer.requiredId).photos).isEmpty()
            제출까지(fixture, ids)

            // when
            val summary = retouchService.get(fixture.galleryId, fixture.photographer.requiredId)
            val detail = retouchService.getRound(fixture.galleryId, 1, fixture.photographer.requiredId)
            val upload = retouchService.issueResultUploadUrls(fixture.galleryId, 1, fixture.photographer.requiredId,
                발급_요청(ids)).uploads.single()
            val completed = retouchService.completeResults(fixture.galleryId, 1, fixture.photographer.requiredId,
                CompleteResultsRequest(results = listOf(CompleteResultsRequest.ResultRequest(photoId = upload.photoId,
                    resultKey = upload.resultKey, contentType = "image/jpeg"))))

            // then
            assertSoftly { softly ->
                softly.assertThat(summary.currentRound!!.photos.single().photo.score).isNull()
                softly.assertThat(detail.photos.single().photo.score).isNull()
                softly.assertThat(completed.photos.single().photo.score).isNull()
                softly.assertThat(retouchService.getRound(fixture.galleryId, 1, fixture.member.requiredId).photos.single().photo.score)
                    .isEqualTo(5)
            }
        }

        @Test
        fun `존재하지 않는 S3 결과가 섞이면 한 장도 완료되지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, ids)
            val uploads = retouchService.issueResultUploadUrls(fixture.galleryId, 1, fixture.photographer.requiredId, 발급_요청(ids)).uploads
            testStorage.missingKeys += uploads.last().resultKey

            // when & then
            try {
                assertThatThrownBy { retouchService.completeResults(fixture.galleryId, 1, fixture.photographer.requiredId,
                    CompleteResultsRequest(results = uploads.map { CompleteResultsRequest.ResultRequest(photoId = it.photoId,
                        resultKey = it.resultKey, contentType = "image/jpeg") })) }
                    .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                    .isEqualTo(RetouchErrorCode.RESULT_UPLOAD_INCOMPLETE)
                assertThat(retouchService.getRound(fixture.galleryId, 1, fixture.photographer.requiredId).photos)
                    .allMatch { it.resultUrl == null }
            } finally {
                testStorage.missingKeys.clear()
            }
        }

        @Test
        fun `같은 결과 파일을 다른 원본에 중복 연결할 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, ids)
            val uploaded = retouchService.issueResultUploadUrls(fixture.galleryId, 1, fixture.photographer.requiredId,
                발급_요청(ids.take(1))).uploads.single()
            retouchService.completeResults(fixture.galleryId, 1, fixture.photographer.requiredId,
                CompleteResultsRequest(results = listOf(CompleteResultsRequest.ResultRequest(photoId = ids.first(),
                    resultKey = uploaded.resultKey, contentType = "image/jpeg"))))

            // when & then
            assertThatThrownBy { retouchService.completeResults(fixture.galleryId, 1, fixture.photographer.requiredId,
                CompleteResultsRequest(results = listOf(CompleteResultsRequest.ResultRequest(photoId = ids.last(),
                    resultKey = uploaded.resultKey, contentType = "image/jpeg")))) }
                .isInstanceOf(RetouchException::class.java).extracting("errorCode")
                .isEqualTo(RetouchErrorCode.DUPLICATE_RESULT)
        }

        @Test
        fun `작가가 보낸 뒤 공개되고 클라이언트 확정은 갤러리를 보관한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            제출까지(fixture, ids)
            결과_확정(fixture, roundNo = 1, photoIds = ids)
            assertThat(retouchService.getRound(fixture.galleryId, 1, fixture.member.requiredId).photos.single().resultUrl).isNull()

            // when
            retouchService.completeRound(fixture.galleryId, 1, fixture.photographer.requiredId)
            val sent = retouchService.getRound(fixture.galleryId, 1, fixture.member.requiredId)
            retouchService.confirm(fixture.galleryId, fixture.member.requiredId)

            // then
            assertThat(sent.photos.single().resultUrl).contains("X-Amz-Signature")
            assertThat(galleryRepository.findById(fixture.galleryId).orElseThrow().stage).isEqualTo(GalleryStage.ARCHIVED)
            assertThatThrownBy {
                retouchService.submitRequests(fixture.galleryId, 2, fixture.member.requiredId, SubmitRetouchRequestsRequest())
            }
                .isInstanceOf(GalleryException::class.java).extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
        }

        @Test
        fun `확장자가 달라도 이름을 매칭하고 불일치에는 수동 후보를 반환한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val ids = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            제출까지(fixture, ids)

            // when
            val result = retouchService.matchResults(fixture.galleryId, 1, fixture.photographer.requiredId,
                MatchRetouchResultsRequest(files = listOf(
                    MatchRetouchResultsRequest.File(filename = "1.PNG", contentType = "image/png"),
                    MatchRetouchResultsRequest.File(filename = "다른 이름.jpg", contentType = "image/jpeg"),
                )))

            // then
            assertThat(result.matches.first().photoId).isEqualTo(ids.first())
            assertThat(result.matches.last().photoId).isNull()
            assertThat(result.matches.last().candidates.map { it.photoId }).containsExactlyElementsOf(ids)
        }
    }

    private fun add(fixture: OpenGallery, photoIds: List<Long>) =
        retouchService.addPhotos(fixture.galleryId, fixture.member.requiredId, AddRetouchPhotosRequest(photoIds))

    /** 셀렉 담기부터 제출까지 — 작가 차례(REQUESTED)의 1회차를 실제 흐름으로 만든다. */
    private fun 제출까지(
        fixture: OpenGallery,
        photoIds: List<Long>,
        requests: List<RetouchRequestItem> = emptyList(),
    ) {
        selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = photoIds))
        selectionService.submit(fixture.galleryId, fixture.member.requiredId, SubmitRetouchRequestsRequest(requests))
    }

    private fun 발급_요청(photoIds: List<Long>, contentType: String = "image/jpeg") =
        IssueResultUploadUrlsRequest(
            photoIds.map { IssueResultUploadUrlsRequest.FileRequest(photoId = it, contentType = contentType) },
        )

    /** 발급→확정을 한 번에. 결과가 있는 항목을 배경으로 만들 때 쓴다. */
    private fun 결과_확정(fixture: OpenGallery, roundNo: Int, photoIds: List<Long>) {
        val issued = retouchService.issueResultUploadUrls(
            fixture.galleryId, roundNo, fixture.photographer.id!!, 발급_요청(photoIds),
        )
        retouchService.completeResults(
            fixture.galleryId, roundNo, fixture.photographer.id!!,
            CompleteResultsRequest(
                issued.uploads.map {
                    CompleteResultsRequest.ResultRequest(
                        photoId = it.photoId,
                        resultKey = it.resultKey,
                        contentType = "image/jpeg",
                    )
                },
            ),
        )
    }
}
