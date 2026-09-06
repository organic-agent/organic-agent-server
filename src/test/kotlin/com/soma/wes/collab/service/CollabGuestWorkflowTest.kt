package com.soma.wes.collab.service

import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoRatingService
import com.soma.wes.support.IntegrationTest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class CollabGuestWorkflowTest @Autowired constructor(
    private val fixtures: CollabFixture,
    private val personalFixtures: PersonalGalleryFixture,
    private val sessionService: CollabSessionService,
    private val queryService: CollabSessionQueryService,
    private val guestService: CollabGuestService,
    private val guestQuery: CollabGuestQueryService,
    private val access: CollabSessionAccess,
    private val sessions: CollabSessionRepository,
    private val likes: CollabPhotoLikeRepository,
    private val comments: CollabPhotoCommentRepository,
    private val photos: PhotoRepository,
    private val ratings: PhotoRatingService,
    private val categories: CategoryService,
    private val clock: Clock,
) {
    @Test
    fun `신규 링크는 칠일 뒤 만료하고 표지 변경은 랜딩과 관리 조회에 유지된다`() {
        val before = ZonedDateTime.now(clock)
        val shared = fixtures.사진이_있는_세션()
        assertThat(shared.session.expiresAt).isAfterOrEqualTo(before.plusDays(7))
            .isBeforeOrEqualTo(ZonedDateTime.now(clock).plusDays(7))
        sessionService.rename(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId,
            RenameCollabSessionRequest(name = "가족 앨범", coverTitle = "함께 고르는 사진", coverAuthor = "예비 부부"))
        val landing = guestQuery.getLanding(shared.token)
        assertThat(landing.coverTitle).isEqualTo("함께 고르는 사진")
        assertThat(landing.coverAuthor).isEqualTo("예비 부부")
        assertThat(queryService.get(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId).coverTitle)
            .isEqualTo("함께 고르는 사진")
        sessionService.rename(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId,
            RenameCollabSessionRequest(name = "이름만 변경"))
        assertThat(guestQuery.getLanding(shared.token).coverAuthor).isEqualTo("예비 부부")
    }

    @Test
    fun `만료된 링크를 다시 열면 새 토큰을 주고 이전 토큰은 더이상 열리지 않는다`() {
        val shared = fixtures.사진이_있는_세션()
        sessions.saveAndFlush(sessions.findById(shared.session.sessionId).orElseThrow().apply {
            expiresAt = ZonedDateTime.now(clock).minusSeconds(1)
        })
        assertThatThrownBy { guestQuery.getLanding(shared.token) }
            .isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(CollabErrorCode.SESSION_EXPIRED)
        val renewed = sessionService.open(shared.galleryId, shared.gallery.member.requiredId,
            OpenCollabSessionRequest(shared.session.conceptFolderId, "다시 공유"))
        assertThat(renewed.sessionId).isEqualTo(shared.session.sessionId)
        assertThat(renewed.collabUrl).isNotEqualTo(shared.session.collabUrl)
        assertThat(renewed.expiresAt).isAfter(ZonedDateTime.now(clock).plusDays(6))
        assertThatThrownBy { guestQuery.getLanding(shared.token) }
            .isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
        assertThat(guestQuery.getLanding(renewed.collabUrl.substringAfterLast('/')).writable).isTrue()
    }

    @Test
    fun `이름을 바꿔도 게스트 토큰과 댓글 좋아요의 참여자는 그대로다`() {
        val shared = fixtures.사진이_있는_세션()
        val guest = guestService.enter(shared.token, EnterCollabRequest("이전 이름"))
        val comment = guestService.writeComment(shared.token, shared.photoId, null, guest.guestToken, WriteCollabCommentRequest("의견"))
        guestService.like(shared.token, shared.photoId, null, guest.guestToken)
        val renamed = guestService.renameGuest(shared.token, guest.guestToken, EnterCollabRequest(" 새 이름 "))
        assertThat(renamed.guestToken).isEqualTo(guest.guestToken)
        assertThat(renamed.participantId).isEqualTo(guest.participantId)
        assertThat(renamed.nickname).isEqualTo("새 이름")
        assertThat(comments.findById(comment.commentId).orElseThrow().participantId).isEqualTo(guest.participantId)
        assertThat(likes.findAllByCollabSessionIdAndPhotoIdInAndParticipantId(shared.session.sessionId, listOf(shared.photoId), guest.participantId))
            .hasSize(1)
        val listedComment = guestQuery.listComments(shared.token, shared.photoId, null, guest.guestToken, 0, 20).contents.single()
        assertThat(listedComment.nickname).isEqualTo("새 이름")
        assertThat(listedComment.mine).isTrue()
        assertThat(guestQuery.listPhotos(shared.token, null, guest.guestToken, 0, 20).contents.single().liked).isTrue()
        guestService.cancelLike(shared.token, shared.photoId, null, guest.guestToken)
        guestService.deleteComment(shared.token, comment.commentId, null, guest.guestToken)
        assertThat(guestQuery.listPhotos(shared.token, null, guest.guestToken, 0, 20).contents.single().likeCount).isZero()
        assertThat(guestQuery.listComments(shared.token, shared.photoId, null, guest.guestToken, 0, 20).contents).isEmpty()
    }

    @Test
    fun `다른 링크의 게스트 토큰과 미입장 요청은 이름을 바꿀 수 없다`() {
        val shared = fixtures.사진이_있는_세션()
        val other = fixtures.사진이_있는_세션()
        val foreign = guestService.enter(other.token, EnterCollabRequest("다른 게스트"))
        for (token in listOf(null, foreign.guestToken)) {
            assertThatThrownBy { guestService.renameGuest(shared.token, token, EnterCollabRequest("잘못된 변경")) }
                .isInstanceOf(CollabException::class.java).extracting("errorCode").isEqualTo(CollabErrorCode.GUEST_NOT_IDENTIFIED)
        }
    }

    @Test
    fun `게스트 사진 정보는 파일과 EXIF를 제공하고 클라이언트 별점은 숨긴다`() {
        val shared = fixtures.사진이_있는_세션()
        val taken = LocalDateTime.of(2026, 9, 1, 14, 30)
        photos.saveAndFlush(photos.findById(shared.photoId).orElseThrow().apply {
            applyMetadata(PhotoMetadata(takenAt = taken, width = 4000, height = 6000, byteSize = 12_000_000L))
        })
        ratings.rate(shared.galleryId, shared.photoId, shared.gallery.member.requiredId, RatePhotoRequest(4))
        val photo = guestQuery.listPhotos(shared.token, null, null, 0, 20).contents.single()
        assertThat(photo.photo.originalFileName).isEqualTo("1.jpg")
        assertThat(photo.photo.contentType).isEqualTo("image/jpeg")
        assertThat(photo.metadata?.takenAt).isEqualTo(taken)
        assertThat(photo.metadata?.width).isEqualTo(4000)
        assertThat(photo.metadata?.height).isEqualTo(6000)
        assertThat(photo.metadata?.byteSize).isEqualTo(12_000_000L)
        assertThat(photo.photo.score).isNull()
    }

    @Test
    fun `작가는 추측한 갤러리 세션 id로 비공개 공유 결과를 조회할 수 없다`() {
        val shared = fixtures.사진이_있는_세션()
        val userId = shared.gallery.photographer.requiredId
        listOf<() -> Any>(
            { queryService.list(shared.galleryId, userId) },
            { queryService.get(shared.galleryId, shared.session.sessionId, userId) },
            { queryService.listPhotos(shared.galleryId, shared.session.sessionId, userId, 0, 20) },
            { queryService.listPhotoComments(shared.galleryId, shared.session.sessionId, shared.photoId, userId, 0, 20) },
        ).forEach { call ->
            assertThatThrownBy { call() }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }

    @Test
    fun `개인 파트너는 소유자와 같은 공유 링크를 관리하고 로그인 참여자가 된다`() {
        val personal = personalFixtures.파트너와_개인_갤러리()
        val concept = categories.createConcept(personal.galleryId, personal.ownerId, CreateConceptFolderRequest("공유 앨범"))
        val session = sessionService.open(personal.galleryId, personal.partnerId, OpenCollabSessionRequest(concept.id, "파트너 공유"))
        assertThat(queryService.get(personal.galleryId, session.sessionId, personal.ownerId).name).isEqualTo("파트너 공유")
        assertThat(queryService.list(personal.galleryId, personal.partnerId)).hasSize(1)
        val accessDto = access.requireReadable(session.collabUrl.substringAfterLast('/'))
        assertThat(access.requireParticipant(accessDto, personal.partnerId, null).userId).isEqualTo(personal.partnerId)
    }
    @Test
    fun `기본 공유 링크는 같은 갤러리에 다른 공유 앨범이 있어도 자기 앨범만 보여준다`() {
        val shared = fixtures.사진이_있는_세션()
        fixtures.사진이_있는_세션(shared.gallery)
        assertThat(guestQuery.getLanding(shared.token).albums.map { it.conceptFolderId })
            .containsExactly(shared.session.conceptFolderId)
    }

    @Test
    fun `전체 앨범 링크는 같은 갤러리의 유효하게 공유된 앨범만 보여준다`() {
        val shared = fixtures.사진이_있는_세션()
        val active = fixtures.사진이_있는_세션(shared.gallery)
        val revoked = fixtures.사진이_있는_세션(shared.gallery)
        val expired = fixtures.사진이_있는_세션(shared.gallery)
        val otherGallery = fixtures.사진이_있는_세션()
        val unshared = categories.createConcept(shared.galleryId, shared.gallery.member.requiredId,
            CreateConceptFolderRequest("공유하지 않은 폴더"))
        sessionService.revoke(shared.galleryId, revoked.session.sessionId, shared.gallery.member.requiredId)
        sessions.saveAndFlush(sessions.findById(expired.session.sessionId).orElseThrow().apply {
            expiresAt = ZonedDateTime.now(clock).minusSeconds(1)
        })
        sessionService.rename(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId,
            RenameCollabSessionRequest(name = "공유 앨범 전체", includeAllAlbums = true))
        val albums = guestQuery.getLanding(shared.token).albums
        assertThat(albums.map { it.conceptFolderId })
            .containsExactlyInAnyOrder(shared.session.conceptFolderId, active.session.conceptFolderId)
            .doesNotContain(revoked.session.conceptFolderId, expired.session.conceptFolderId,
                otherGallery.session.conceptFolderId, unshared.id)
        assertThat(albums.map { it.collabToken }).containsExactlyInAnyOrder(shared.token, active.token)
        assertThat(guestQuery.listPhotos(active.token, null, null, 0, 20).contents.map { it.photoId })
            .containsExactly(active.photoId)
    }
}
