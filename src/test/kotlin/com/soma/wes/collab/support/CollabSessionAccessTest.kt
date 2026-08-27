package com.soma.wes.collab.support

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 계정 없는 요청의 세 문을 확인한다 — 토큰이 곧 자격이다.
 *
 * 보는 것은 셋이다. **보는 문이 딱 그만큼만 열려 있는가**(없는 토큰·폐기·DRAFT가 각각 다른
 * 답으로 막히는지), **남기는 문이 부부의 선택 기한과 같이 움직이는가**, 그리고 **글쓴이 확인이
 * 세션 단위로 갈라지는가**(남의 세션 토큰은 없는 토큰과 같다).
 */
@IntegrationTest
class CollabSessionAccessTest @Autowired constructor(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val galleryFixture: GalleryFixture,
    private val galleryRepository: GalleryRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val clock: Clock,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("링크로 볼 수 있는지 물을 때")
    inner class Readable {

        @Test
        fun `살아 있는 링크는 세션과 갤러리를 함께 돌려준다`() {
            // given
            val session = openSession()

            // when
            val access = collabSessionAccess.requireReadable(session.collabToken)

            // then
            // 확인한 세션과 갤러리를 함께 들려준다 — 호출부가 다시 읽으면 확인과 사용이 어긋난다.
            assertSoftly { softly ->
                softly.assertThat(access.sessionId).isEqualTo(session.sessionId)
                softly.assertThat(access.session.name).isEqualTo("하객에게")
                softly.assertThat(access.gallery.id).isEqualTo(fixture.galleryId)
            }
        }

        @Test
        fun `발급한 적 없는 토큰은 열리지 않는다`() {
            // when & then
            assertThatThrownBy { collabSessionAccess.requireReadable("never-issued") }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
        }

        @Test
        fun `폐기한 링크는 보는 것부터 막힌다`() {
            // 폐기는 주소 자체를 죽이는 결정이라 읽기 문도 닫힌다. 404가 아니라 410이다 —
            // "우리가 발급했던 링크가 맞고 지금은 쓸 수 없다"를 알아야 새 링크를 안내할 수 있다.
            // given
            val session = openSession()
            collabSessionService.revoke(fixture.galleryId, session.sessionId, fixture.member.id!!)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireReadable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_REVOKED)
        }

        @Test
        fun `재발급 링크의 만료 시각이 지나면 읽기와 쓰기가 모두 막힌다`() {
            // given
            val session = openSession()
            val entity = collabSessionRepository.findById(session.sessionId).orElseThrow()
            entity.expiresAt = ZonedDateTime.now(clock).minusSeconds(1)
            collabSessionRepository.saveAndFlush(entity)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireReadable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_EXPIRED)
            assertThatThrownBy { collabSessionAccess.requireWritable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_EXPIRED)
        }

        @Test
        fun `재발급 링크의 만료 시각이 남아 있으면 열린다`() {
            // given
            val session = openSession()
            val entity = collabSessionRepository.findById(session.sessionId).orElseThrow()
            entity.expiresAt = ZonedDateTime.now(clock).plusMinutes(5)
            collabSessionRepository.saveAndFlush(entity)

            // when & then
            assertThat(collabSessionAccess.requireReadable(session.collabToken).sessionId)
                .isEqualTo(session.sessionId)
        }

        @Test
        fun `갤러리가 DRAFT로 돌아가면 링크로도 열리지 않는다`() {
            // 정상 흐름에는 없는 상태다 — 세션을 여는 것부터가 부부의 동작이라 갤러리는 열려
            // 있다. DRAFT로 되돌리는 경로가 생기는 날, 링크 하나로 그 갤러리가 다시 공개되지
            // 않도록 막는 문이라 상태를 직접 되돌려 확인한다.
            // given
            val session = openSession()
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.status = GalleryStatus.DRAFT
            galleryRepository.saveAndFlush(gallery)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireReadable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_NOT_READY)
        }
    }

    @Nested
    @DisplayName("링크로 의견을 남길 수 있는지 물을 때")
    inner class Writable {

        @Test
        fun `부부가 고를 수 있는 동안에는 열려 있다`() {
            // given
            val session = openSession()

            // when
            val access = collabSessionAccess.requireWritable(session.collabToken)

            // then
            assertThat(access.sessionId).isEqualTo(session.sessionId)
            // 화면이 댓글창을 띄울지 정하는 값도 같은 판단에서 나온다.
            assertThat(collabSessionAccess.isWritable(access)).isTrue()
        }

        @Test
        fun `마감이 지나면 남길 수 없지만 보는 것은 계속 된다`() {
            // given
            val session = openSession()
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireWritable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FEEDBACK_CLOSED)

            // 하객이 자기가 남긴 말과 사진을 다시 열어보는 것까지 막을 이유가 없다.
            val access = collabSessionAccess.requireReadable(session.collabToken)
            assertThat(collabSessionAccess.isWritable(access)).isFalse()
        }

        @Test
        fun `갤러리가 마감되면 기한이 남았어도 남길 수 없다`() {
            // given
            val session = openSession()
            val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
            gallery.close()
            galleryRepository.saveAndFlush(gallery)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireWritable(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FEEDBACK_CLOSED)
        }
    }

    @Nested
    @DisplayName("글쓴이를 확인할 때")
    inner class IdentifyGuest {

        @Test
        fun `입장하며 받은 토큰이면 그 하객이 나온다`() {
            // given
            val session = openSession()
            val guestToken = enter(session.collabToken, "신부 친구 영희")
            val access = collabSessionAccess.requireReadable(session.collabToken)

            // when
            val guest = collabSessionAccess.requireGuest(access, guestToken)

            // then
            assertThat(guest.nickname).isEqualTo("신부 친구 영희")
            assertThat(guest.collabSessionId).isEqualTo(access.sessionId)
        }

        @Test
        fun `토큰이 없으면 하객으로 인정하지 않는다`() {
            // 없는 것과 틀린 것을 구분하지 않는다 — 화면이 할 일은 어느 쪽이든 닉네임을 다시 받는 것이다.
            // given
            val access = collabSessionAccess.requireReadable(openSession().collabToken)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireGuest(access, guestToken = null) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }

        @Test
        fun `다른 세션에서 받은 토큰은 없는 토큰과 같다`() {
            // 한 하객이 여러 결혼식 링크를 받을 수 있다. 토큰만 보면 A에서 받은 것으로 B에 쓴다.
            // given
            val session = openSession()
            val other = galleryFixture.멤버와_열린_갤러리()
            val otherSession = openSession(other)
            val strangerToken = enter(otherSession.collabToken, "옆 결혼식 하객")
            val access = collabSessionAccess.requireReadable(session.collabToken)

            // when & then
            assertThatThrownBy { collabSessionAccess.requireGuest(access, strangerToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }

        @Test
        fun `보기 경로는 토큰이 없어도 익명으로 통과한다`() {
            // 보는 것을 막을 이유가 없다 — 화면에서 "내가 누른 반응" 표시만 비어 보인다.
            // given
            val session = openSession()
            val other = galleryFixture.멤버와_열린_갤러리()
            val otherSession = openSession(other)
            val mine = enter(session.collabToken, "영희")
            val strangers = enter(otherSession.collabToken, "옆 결혼식 하객")
            val access = collabSessionAccess.requireReadable(session.collabToken)

            // when & then
            assertSoftly { softly ->
                softly.assertThat(collabSessionAccess.findGuest(access, guestToken = null)).isNull()
                softly.assertThat(collabSessionAccess.findGuest(access, strangers)).isNull()
                softly.assertThat(collabSessionAccess.findGuest(access, mine)?.nickname).isEqualTo("영희")
            }
        }
    }

    // --- helpers ---

    /** 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 문을 두드리려면 거기서 떼어낸다. */
    private val CollabSessionResponse.collabToken: String
        get() = collabUrl.substringAfterLast('/')

    private fun openSession(target: OpenGallery = fixture, name: String = "하객에게"): CollabSessionResponse =
        collabSessionService.open(target.galleryId, target.member.id!!, OpenCollabSessionRequest(name = name))

    private fun enter(collabToken: String, nickname: String): String =
        collabGuestService.enter(collabToken, EnterCollabRequest(nickname)).guestToken
}
