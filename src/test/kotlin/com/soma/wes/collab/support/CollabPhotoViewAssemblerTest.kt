package com.soma.wes.collab.support

import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabPhotoResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
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
import java.time.ZonedDateTime

/**
 * 협업 사진 한 페이지의 화면 규칙을 확인한다.
 *
 * 보는 것은 셋이다. **집계가 사람 수 그대로인가**(좋아요·댓글 수, 없는 자리는 0),
 * **"내가 눌렀다"가 토큰을 든 그 하객에게만 켜지는가**, 그리고 **부부끼리의 정보가
 * 하객 화면으로 새지 않는가**(별점은 늘 비어 있다, 휴지통 사진은 접힌다).
 */
@IntegrationTest
class CollabPhotoViewAssemblerTest @Autowired constructor(
    private val collabPhotoViewAssembler: CollabPhotoViewAssembler,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val collabGuestRepository: CollabGuestRepository,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("반응을 집계할 때")
    inner class Aggregation {

        @Test
        fun `좋아요 수와 댓글 수가 사진마다 따로 모인다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val session = openSession()
            val collabPhotoIds = addPhotos(session.sessionId, photoIds)
            val first = collabPhotoIds[0]
            collabGuestService.like(session.collabToken, first, enter(session.collabToken, "하객1"))
            collabGuestService.like(session.collabToken, first, enter(session.collabToken, "하객2"))
            collabGuestService.writeComment(
                session.collabToken, first, enter(session.collabToken, "하객3"), WriteCollabCommentRequest("이게 최고"),
            )

            // when
            val page = collabPhotoViewAssembler.toPage(session.sessionId, page = 0, size = 20)

            // then
            assertSoftly { softly ->
                softly.assertThat(page.totalCount).isEqualTo(2L)
                softly.assertThat(byCollabPhotoId(page.contents, first).likeCount).isEqualTo(2L)
                softly.assertThat(byCollabPhotoId(page.contents, first).commentCount).isEqualTo(1L)
                // 반응이 하나도 없는 사진은 집계 결과에 아예 없다 — 그 자리를 0으로 채운다.
                softly.assertThat(byCollabPhotoId(page.contents, collabPhotoIds[1]).likeCount).isEqualTo(0L)
                softly.assertThat(byCollabPhotoId(page.contents, collabPhotoIds[1]).commentCount).isEqualTo(0L)
                // 버킷이 비공개라 서명 URL 없이는 아무것도 띄울 수 없다.
                softly.assertThat(page.contents[0].photo.viewUrl).contains("X-Amz-Signature")
                softly.assertThat(page.viewUrlTtlSeconds).isEqualTo(900L)
            }
        }

        @Test
        fun `빈 세션은 빈 페이지다`() {
            // given
            val session = openSession()

            // when
            val page = collabPhotoViewAssembler.toPage(session.sessionId, page = 0, size = 20)

            // then
            assertThat(page.contents).isEmpty()
            assertThat(page.totalCount).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("누가 보는지에 따라")
    inner class Viewer {

        @Test
        fun `guestId가 있으면 그 하객이 누른 사진에만 liked가 켜진다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val session = openSession()
            val collabPhotoIds = addPhotos(session.sessionId, photoIds)
            val mine = enter(session.collabToken, "영희")
            collabGuestService.like(session.collabToken, collabPhotoIds[0], mine)
            collabGuestService.like(session.collabToken, collabPhotoIds[1], enter(session.collabToken, "철수"))

            // when
            val page = collabPhotoViewAssembler.toPage(
                session.sessionId, page = 0, size = 20, guestId = guestId(session.sessionId, mine),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(byCollabPhotoId(page.contents, collabPhotoIds[0]).liked).isTrue()
                // 남이 누른 좋아요는 수에는 잡히지만 내 표시로 켜지지 않는다.
                softly.assertThat(byCollabPhotoId(page.contents, collabPhotoIds[1]).liked).isFalse()
                softly.assertThat(byCollabPhotoId(page.contents, collabPhotoIds[1]).likeCount).isEqualTo(1L)
            }
        }

        @Test
        fun `guestId가 없으면 liked는 전부 false다`() {
            // 부부와 작가가 결과를 볼 때가 이 경우다 — 그들은 하객이 아니라 좋아요를 남기지 않는다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 1)
            val session = openSession()
            val collabPhotoId = addPhotos(session.sessionId, photoIds).single()
            collabGuestService.like(session.collabToken, collabPhotoId, enter(session.collabToken, "친구"))

            // when
            val page = collabPhotoViewAssembler.toPage(session.sessionId, page = 0, size = 20, guestId = null)

            // then
            assertThat(page.contents[0].liked).isFalse()
            assertThat(page.contents[0].likeCount).isEqualTo(1L)
        }

        @Test
        fun `별점은 어느 화면에도 실리지 않는다`() {
            // 별점은 부부와 작가가 고르며 서로에게 남기는 표시다. 이 조립기는 하객 화면과 결과
            // 화면이 함께 쓰므로, 사진에 찍힌 하객이 자기 점수를 읽는 일이 없도록 늘 비운다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            photoRatingRepository.save(PhotoRating.of(photoId = photoId, score = 2, ratedBy = fixture.member.id!!))
            val session = openSession()
            addPhotos(session.sessionId, listOf(photoId))

            // when
            val page = collabPhotoViewAssembler.toPage(session.sessionId, page = 0, size = 20)

            // then
            assertThat(page.contents[0].photo.score).isNull()
        }
    }

    @Nested
    @DisplayName("사진이 휴지통에 들어가면")
    inner class TrashedPhoto {

        @Test
        fun `그 자리가 조용히 접힌다`() {
            // `findAllById`가 휴지통 사진을 걸러내고 조립이 그 자리를 접는다. 총계는 아직
            // `collab_photos` 행 수라 표시 수보다 크게 나온다 — 조립기가 문서로 남겨 둔 MVP 동작이다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val session = openSession()
            addPhotos(session.sessionId, photoIds)
            val trashed = photoRepository.findById(photoIds[0]).orElseThrow()
            trashed.moveToTrash(ZonedDateTime.now())
            photoRepository.saveAndFlush(trashed)

            // when
            val page = collabPhotoViewAssembler.toPage(session.sessionId, page = 0, size = 20)

            // then
            assertSoftly { softly ->
                softly.assertThat(page.contents).hasSize(1)
                softly.assertThat(page.contents[0].photo.photoId).isEqualTo(photoIds[1])
                softly.assertThat(page.totalCount).isEqualTo(2L)
            }
        }
    }

    // --- helpers ---

    /** 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 하객 경로를 부르려면 거기서 떼어낸다. */
    private val CollabSessionResponse.collabToken: String
        get() = collabUrl.substringAfterLast('/')

    private fun openSession(name: String = "하객에게"): CollabSessionResponse =
        collabSessionService.open(fixture.galleryId, fixture.member.id!!, OpenCollabSessionRequest(name = name))

    private fun addPhotos(sessionId: Long, photoIds: List<Long>): List<Long> =
        collabSessionService.addPhotos(
            fixture.galleryId, sessionId, fixture.member.id!!, AddCollabPhotosRequest(photoIds),
        ).contents.map { it.collabPhotoId }

    private fun enter(collabToken: String, nickname: String): String =
        collabGuestService.enter(collabToken, EnterCollabRequest(nickname)).guestToken

    /** 조립기는 토큰이 아니라 이미 확인된 하객 id를 받는다 — 토큰 해석은 `CollabSessionAccess`의 몫이다. */
    private fun guestId(sessionId: Long, guestToken: String): Long =
        collabGuestRepository.findByGuestTokenAndCollabSessionId(guestToken, sessionId)!!.requiredId

    private fun byCollabPhotoId(contents: List<CollabPhotoResponse>, collabPhotoId: Long): CollabPhotoResponse =
        contents.single { it.collabPhotoId == collabPhotoId }
}
