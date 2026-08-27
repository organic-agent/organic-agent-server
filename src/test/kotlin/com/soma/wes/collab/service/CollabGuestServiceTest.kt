package com.soma.wes.collab.service

import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 하객 쪽 입장·댓글·좋아요를 서비스 경계에서 확인한다.
 *
 * 보는 것은 셋이다. **토큰만 든 요청이 딱 그만큼만 열려 있는가**(없는 토큰·남의 세션·마감이
 * 각각 막히는지), **하객 한 사람의 표가 하나로 유지되는가**, 그리고 **부부끼리의 정보가 하객
 * 화면으로 새지 않는가**(별점).
 */
@IntegrationTest
class CollabGuestServiceTest @Autowired constructor(
    private val collabGuestService: CollabGuestService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val collabSessionService: CollabSessionService,
    private val collabSessionQueryService: CollabSessionQueryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val photoRatingRepository: PhotoRatingRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoLikeRepository: CollabPhotoLikeRepository,
    private val jdbcClient: JdbcClient,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("하객이 링크로 들어올 때")
    inner class GuestEntry {

        @Test
        fun `링크만으로 첫 화면이 열린다`() {
            // given
            val session = openSession(fixture)
            addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 2))

            // when
            val landing = collabGuestQueryService.getLanding(session.collabToken)

            // then
            assertSoftly { softly ->
                softly.assertThat(landing.galleryTitle).isEqualTo("본식")
                softly.assertThat(landing.photoCount).isEqualTo(2L)
                softly.assertThat(landing.writable).isTrue()
            }
        }

        @Test
        fun `발급한 적 없는 토큰은 열리지 않는다`() {
            // when & then
            assertThatThrownBy { collabGuestQueryService.getLanding("never-issued") }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
        }

        @Test
        fun `하객에게는 부부가 매긴 별점이 보이지 않는다`() {
            // 별점은 부부와 작가가 고르며 서로에게 남기는 표시다. 사진에 찍힌 하객이 자기 사진의
            // 점수를 보게 되는 일까지 생긴다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            photoRatingRepository.save(PhotoRating.of(photoId = photoId, score = 2, ratedBy = fixture.member.id!!))
            val session = openSession(fixture)
            addPhotos(fixture, session.sessionId, listOf(photoId))

            // when
            val guestView = collabGuestQueryService.listPhotos(session.collabToken, guestToken = null, page = 0, size = 20)

            // then
            assertThat(guestView.contents[0].photo.score).isNull()
        }

        @Test
        fun `닉네임을 적으면 하객 토큰이 발급된다`() {
            // given
            val session = openSession(fixture)

            // when
            val guest = collabGuestService.enter(session.collabToken, EnterCollabRequest(nickname = "신부 친구 영희"))

            // then
            assertThat(guest.nickname).isEqualTo("신부 친구 영희")
            assertThat(guest.guestToken).isNotBlank()
        }

        @Test
        fun `닉네임이 비면 입장할 수 없다`() {
            // given
            val session = openSession(fixture)

            // when & then
            assertThatThrownBy { collabGuestService.enter(session.collabToken, EnterCollabRequest(nickname = "   ")) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.INVALID_NICKNAME)
        }
    }

    @Nested
    @DisplayName("댓글을 남기고 지울 때")
    inner class Comments {

        @Test
        fun `하객이 댓글을 남기면 목록에 자기 것으로 표시된다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val mine = enter(session.collabToken, "영희")
            val others = enter(session.collabToken, "철수")
            writeComment(session.collabToken, collabPhotoId, mine, "이 표정이 제일 신부님답네요")
            writeComment(session.collabToken, collabPhotoId, others, "저는 옆 사진이 더 좋아요")

            // when
            val comments = collabGuestQueryService.listComments(
                session.collabToken, collabPhotoId, guestToken = mine, page = 0, size = 20,
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(comments.totalCount).isEqualTo(2L)
                // 최근 것이 위로 온다.
                softly.assertThat(comments.contents[0].nickname).isEqualTo("철수")
                softly.assertThat(comments.contents[0].mine).isFalse()
                softly.assertThat(comments.contents[1].nickname).isEqualTo("영희")
                softly.assertThat(comments.contents[1].mine).isTrue()
            }
        }

        @Test
        fun `토큰 없이는 댓글을 남길 수 없다`() {
            // 보는 것은 토큰 없이 되고, 남기는 것만 "당신이 누구인지"를 먼저 묻는다.
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()

            // when & then
            assertThatThrownBy {
                collabGuestService.writeComment(
                    session.collabToken, collabPhotoId, guestToken = null, WriteCollabCommentRequest("익명으로 한마디"),
                )
            }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }

        @Test
        fun `다른 세션에서 받은 토큰으로는 글을 남길 수 없다`() {
            // 한 하객이 여러 결혼식 링크를 받을 수 있다. 토큰만 보면 A에서 받은 것으로 B에 쓴다.
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val session = openSession(fixture)
            val otherSession = openSession(other)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val otherGuest = enter(otherSession.collabToken, "옆 결혼식 하객")

            // when & then
            assertThatThrownBy {
                collabGuestService.writeComment(
                    session.collabToken, collabPhotoId, otherGuest, WriteCollabCommentRequest("여긴 어디"),
                )
            }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }

        @Test
        fun `하객은 자기 댓글만 지운다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val mine = enter(session.collabToken, "영희")
            val others = enter(session.collabToken, "철수")
            val commentId = writeComment(session.collabToken, collabPhotoId, mine, "지울 댓글")

            // when & then
            assertThatThrownBy { collabGuestService.deleteComment(session.collabToken, commentId, others) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.COMMENT_NOT_OWNED)

            collabGuestService.deleteComment(session.collabToken, commentId, mine)
            assertThat(collabPhotoCommentRepository.count()).isEqualTo(0L)

            val trash = childTrash("COLLAB_COMMENT", commentId)
            assertSoftly { softly ->
                softly.assertThat(rawDeleted("collab_photo_comments", commentId)).isTrue()
                softly.assertThat(rawVersion("collab_photo_comments", commentId)).isEqualTo(1L)
                softly.assertThat(trash.parentId).isEqualTo(session.sessionId)
                softly.assertThat(trash.actorAdminIdNull).isTrue()
                softly.assertThat(trash.actorLabel).isEqualTo("PRODUCT_GUEST")
                softly.assertThat(trash.reason).isEqualTo("PRODUCT_GUEST_SELF_DELETE")
                softly.assertThat(trash.status).isEqualTo("ACTIVE")
                softly.assertThat(trash.hasSevenDayWindow).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("좋아요를 누를 때")
    inner class Likes {

        @Test
        fun `같은 하객이 여러 번 눌러도 좋아요는 하나다`() {
            // 새로고침할 때마다 표가 쌓이면 "좋아요 40"이 사람 40명이 아니게 된다.
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")

            // when
            repeat(3) { collabGuestService.like(session.collabToken, collabPhotoId, guestToken) }

            // then
            assertThat(collabPhotoLikeRepository.count()).isEqualTo(1L)
            val guestView = collabGuestQueryService.listPhotos(session.collabToken, guestToken, page = 0, size = 20)
            assertThat(guestView.contents[0].likeCount).isEqualTo(1L)
            assertThat(guestView.contents[0].liked).isTrue()
        }

        @Test
        fun `하객마다 좋아요 하나씩 쌓이고 부부는 그 수를 본다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            collabGuestService.like(session.collabToken, collabPhotoId, enter(session.collabToken, "하객1"))
            collabGuestService.like(session.collabToken, collabPhotoId, enter(session.collabToken, "하객2"))
            collabGuestService.like(session.collabToken, collabPhotoId, enter(session.collabToken, "하객3"))

            // when
            val coupleView = collabSessionQueryService.listPhotos(
                fixture.galleryId, session.sessionId, fixture.member.id!!, page = 0, size = 20,
            )

            // then
            assertThat(coupleView.contents[0].likeCount).isEqualTo(3L)
            // 부부와 작가는 하객이 아니라 좋아요를 남기지 않는다.
            assertThat(coupleView.contents[0].liked).isFalse()
        }

        @Test
        fun `좋아요는 취소할 수 있고 누른 적 없어도 성공한다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")

            // when & then
            collabGuestService.cancelLike(session.collabToken, collabPhotoId, guestToken)

            collabGuestService.like(session.collabToken, collabPhotoId, guestToken)
            val likeId = jdbcClient.sql(
                "SELECT id FROM collab_photo_likes WHERE collab_photo_id = :photoId AND deleted_at IS NULL",
            ).param("photoId", collabPhotoId).query { rs, _ -> rs.getLong("id") }.single()
            collabGuestService.cancelLike(session.collabToken, collabPhotoId, guestToken)

            assertThat(collabPhotoLikeRepository.count()).isEqualTo(0L)
            val trash = childTrash("COLLAB_LIKE", likeId)
            assertSoftly { softly ->
                softly.assertThat(rawDeleted("collab_photo_likes", likeId)).isTrue()
                softly.assertThat(rawVersion("collab_photo_likes", likeId)).isEqualTo(1L)
                softly.assertThat(trash.parentId).isEqualTo(session.sessionId)
                softly.assertThat(trash.actorAdminIdNull).isTrue()
                softly.assertThat(trash.actorLabel).isEqualTo("PRODUCT_GUEST")
                softly.assertThat(trash.reason).isEqualTo("PRODUCT_GUEST_LIKE_CANCEL")
                softly.assertThat(trash.status).isEqualTo("ACTIVE")
                softly.assertThat(trash.hasSevenDayWindow).isTrue()
            }

            // 취소된 행은 7일 복원을 위해 남아 있지만 활성 유니크 계약은 재좋아요를 허용한다.
            collabGuestService.like(session.collabToken, collabPhotoId, guestToken)
            assertThat(collabPhotoLikeRepository.count()).isEqualTo(1L)
            assertThat(
                jdbcClient.sql("SELECT COUNT(*) FROM collab_photo_likes WHERE collab_photo_id = :photoId")
                    .param("photoId", collabPhotoId).query { rs, _ -> rs.getLong(1) }.single(),
            ).isEqualTo(2L)
        }

        @Test
        fun `관리자 휴지통 좋아요는 사용자 집계에서 숨고 하객은 다시 누를 수 있다`() {
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")
            collabGuestService.like(session.collabToken, collabPhotoId, guestToken)
            val likeId = jdbcClient.sql(
                "SELECT id FROM collab_photo_likes WHERE collab_photo_id = :photoId",
            ).param("photoId", collabPhotoId).query { rs, _ -> rs.getLong(1) }.single()
            jdbcClient.sql(
                "UPDATE collab_photo_likes SET deleted_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = :id",
            ).param("id", likeId).update()

            val hidden = collabGuestQueryService.listPhotos(
                session.collabToken, guestToken, page = 0, size = 20,
            ).contents.single()
            assertThat(hidden.likeCount).isZero()
            assertThat(hidden.liked).isFalse()
            assertThat(collabPhotoLikeRepository.count()).isZero()

            collabGuestService.like(session.collabToken, collabPhotoId, guestToken)

            val reliked = collabGuestQueryService.listPhotos(
                session.collabToken, guestToken, page = 0, size = 20,
            ).contents.single()
            assertThat(reliked.likeCount).isOne()
            assertThat(reliked.liked).isTrue()
            assertThat(
                jdbcClient.sql("SELECT COUNT(*) FROM collab_photo_likes WHERE collab_photo_id = :photoId")
                    .param("photoId", collabPhotoId).query { rs, _ -> rs.getLong(1) }.single(),
            ).isEqualTo(2L)
        }

        @Test
        fun `남의 세션 사진에는 좋아요를 남길 수 없다`() {
            // 링크는 갤러리마다 다르지만 협업 사진 id는 전역에서 이어지는 값이다.
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val session = openSession(fixture)
            val otherSession = openSession(other)
            addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1))
            val othersCollabPhotoId =
                addPhotos(other, otherSession.sessionId, photoFixture.업로드된_사진(other.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")

            // when & then
            assertThatThrownBy { collabGuestService.like(session.collabToken, othersCollabPhotoId, guestToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)

            // 다른 세션에서는 정상적으로 눌린다 — id 자체가 없는 것이 아니다.
            assertThat(collabPhotoRepository.findAllByCollabSessionId(otherSession.sessionId)).hasSize(1)
        }
    }

    @Nested
    @DisplayName("마감된 뒤에")
    inner class AfterDeadline {

        @Test
        fun `보기만 되고 남길 수는 없다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")
            writeComment(session.collabToken, collabPhotoId, guestToken, "마감 전에 남긴 말")
            galleryFixture.마감_지남(fixture.galleryId)

            // when & then
            // 화면은 이 값을 보고 댓글창을 감춘다. 프론트가 마감 시각으로 따로 계산하면
            // 서버가 막는 기준과 어긋나는 날이 온다.
            assertThat(collabGuestQueryService.getLanding(session.collabToken).writable).isFalse()
            val comments = collabGuestQueryService.listComments(
                session.collabToken, collabPhotoId, guestToken = null, page = 0, size = 20,
            )
            assertThat(comments.totalCount).isEqualTo(1L)

            assertThatThrownBy {
                collabGuestService.writeComment(
                    session.collabToken, collabPhotoId, guestToken, WriteCollabCommentRequest("늦게 온 말"),
                )
            }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FEEDBACK_CLOSED)
            assertThatThrownBy { collabGuestService.like(session.collabToken, collabPhotoId, guestToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FEEDBACK_CLOSED)
        }
    }

    // --- helpers ---

    /** 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 하객 경로를 부르려면 거기서 떼어낸다. */
    private val CollabSessionResponse.collabToken: String
        get() = collabUrl.substringAfterLast('/')

    private fun openSession(fixture: OpenGallery, name: String = "하객에게"): CollabSessionResponse =
        collabSessionService.open(fixture.galleryId, fixture.member.id!!, OpenCollabSessionRequest(name = name))

    private fun addPhotos(fixture: OpenGallery, sessionId: Long, photoIds: List<Long>): List<Long> =
        collabSessionService.addPhotos(
            fixture.galleryId, sessionId, fixture.member.id!!, AddCollabPhotosRequest(photoIds),
        ).contents.map { it.collabPhotoId }

    private fun enter(collabToken: String, nickname: String): String =
        collabGuestService.enter(collabToken, EnterCollabRequest(nickname)).guestToken

    private fun writeComment(collabToken: String, collabPhotoId: Long, guestToken: String, content: String): Long =
        collabGuestService.writeComment(
            collabToken, collabPhotoId, guestToken, WriteCollabCommentRequest(content),
        ).commentId

    private fun rawDeleted(table: String, id: Long): Boolean = jdbcClient.sql(
        "SELECT deleted_at IS NOT NULL FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getBoolean(1) }.single()

    private fun rawVersion(table: String, id: Long): Long = jdbcClient.sql(
        "SELECT version FROM $table WHERE id = :id",
    ).param("id", id).query { rs, _ -> rs.getLong(1) }.single()

    private fun childTrash(resourceType: String, resourceId: Long): ProductTrashSnapshot = jdbcClient.sql(
        """
        SELECT parent_id, actor_admin_id IS NULL AS actor_admin_id_null, actor_username, reason, status,
               restore_until = deleted_at + INTERVAL '7 days' AS has_seven_day_window
        FROM admin_child_trash_records
        WHERE resource_type = :resourceType AND resource_id = :resourceId AND status = 'ACTIVE'
        """.trimIndent(),
    )
        .param("resourceType", resourceType)
        .param("resourceId", resourceId)
        .query { rs, _ ->
            ProductTrashSnapshot(
                parentId = rs.getLong("parent_id"),
                actorAdminIdNull = rs.getBoolean("actor_admin_id_null"),
                actorLabel = rs.getString("actor_username"),
                reason = rs.getString("reason"),
                status = rs.getString("status"),
                hasSevenDayWindow = rs.getBoolean("has_seven_day_window"),
            )
        }
        .single()

    private data class ProductTrashSnapshot(
        val parentId: Long,
        val actorAdminIdNull: Boolean,
        val actorLabel: String,
        val reason: String,
        val status: String,
        val hasSevenDayWindow: Boolean,
    )
}
