package com.soma.wes.collab.service

import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.fixture.SharedCollab
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class CollabSessionQueryServiceTest @Autowired constructor(
    private val queryService: CollabSessionQueryService,
    private val sessionService: CollabSessionService,
    private val guestService: CollabGuestService,
    private val collabFixture: CollabFixture,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val galleryRepository: GalleryRepository,
    private val sessionRepository: CollabSessionRepository,
    private val photoRepository: PhotoRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val commentRepository: CollabPhotoCommentRepository,
) {
    private lateinit var shared: SharedCollab

    @BeforeEach
    fun setUpBaseData() {
        shared = collabFixture.사진이_있는_세션()
    }

    @Nested
    @DisplayName("공유 사진의 댓글 결과를 읽을 때")
    inner class Reading {
        @Test
        fun `두 부부는 닉네임과 최신순 페이지를 읽고 작가에게는 결과를 공개하지 않는다`() {
            // given
            val partner = galleryFixture.멤버(shared.galleryId)
            val guest = guestService.enter(shared.token, EnterCollabRequest(nickname = "친구"))
            val first = guestService.writeComment(
                shared.token, shared.photoId, null, guest.guestToken, WriteCollabCommentRequest(content = "첫 의견"),
            )
            val second = guestService.writeComment(
                shared.token, shared.photoId, shared.gallery.member.requiredId, null,
                WriteCollabCommentRequest(content = "부부 의견"),
            )

            // when
            val mine = queryService.listPhotoComments(
                shared.galleryId, shared.session.sessionId, shared.photoId, shared.gallery.member.requiredId, 0, 1,
            )
            val partners = queryService.listPhotoComments(
                shared.galleryId, shared.session.sessionId, shared.photoId, partner.requiredId, 1, 1,
            )
            assertThatThrownBy {
                queryService.listPhotoComments(
                    shared.galleryId, shared.session.sessionId, shared.photoId, shared.gallery.photographer.requiredId, 0, 50,
                )
            }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)

            // then
            assertSoftly { softly ->
                softly.assertThat(mine.totalCount).isEqualTo(2L)
                softly.assertThat(mine.hasNext).isTrue()
                softly.assertThat(mine.contents.single().commentId).isEqualTo(second.commentId)
                softly.assertThat(mine.contents.single().mine).isTrue()
                softly.assertThat(partners.contents.single().commentId).isEqualTo(first.commentId)
                softly.assertThat(partners.contents.single().nickname).isEqualTo("친구")
                softly.assertThat(partners.contents.single().mine).isFalse()
                softly.assertThat(partners.hasNext).isFalse()
            }
        }

        @Test
        fun `갤러리 마감과 링크 폐기 후에도 부부는 결과를 읽는다`() {
            // given
            val guest = guestService.enter(shared.token, EnterCollabRequest(nickname = "가족"))
            guestService.writeComment(
                shared.token, shared.photoId, null, guest.guestToken, WriteCollabCommentRequest(content = "남은 의견"),
            )
            val gallery = galleryRepository.findById(shared.galleryId).orElseThrow()
            gallery.status = GalleryStatus.CLOSED
            galleryRepository.saveAndFlush(gallery)
            galleryFixture.마감_지남(shared.galleryId)
            sessionService.revoke(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId)

            // when
            val result = queryService.listPhotoComments(
                shared.galleryId, shared.session.sessionId, shared.photoId, shared.gallery.member.requiredId, 0, 50,
            )

            // then
            assertThat(result.contents.single().content).isEqualTo("남은 의견")
        }

        @Test
        fun `삭제된 댓글은 결과와 전체 개수에서 빠진다`() {
            // given
            val guest = guestService.enter(shared.token, EnterCollabRequest(nickname = "친구"))
            val response = guestService.writeComment(
                shared.token, shared.photoId, null, guest.guestToken, WriteCollabCommentRequest(content = "삭제한 의견"),
            )
            val comment = commentRepository.findById(response.commentId).orElseThrow()
            comment.deletedAt = ZonedDateTime.now()
            commentRepository.saveAndFlush(comment)

            // when
            val result = queryService.listPhotoComments(
                shared.galleryId, shared.session.sessionId, shared.photoId, shared.gallery.member.requiredId, -1, 500,
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.page).isZero()
                softly.assertThat(result.size).isEqualTo(200)
                softly.assertThat(result.totalCount).isZero()
                softly.assertThat(result.contents).isEmpty()
            }
        }
    }

    @Nested
    @DisplayName("공유 댓글의 접근 범위를 검증할 때")
    inner class AccessBoundary {
        @Test
        fun `비회원과 다른 갤러리 멤버는 읽을 수 없다`() {
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val stranger = userFixture.사용자()

            // when & then
            listOf(stranger.requiredId, other.member.requiredId).forEach { userId ->
                assertThatThrownBy {
                    queryService.listPhotoComments(shared.galleryId, shared.session.sessionId, shared.photoId, userId, 0, 50)
                }.isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }

        @Test
        fun `다른 갤러리 세션과 사진을 섞을 수 없다`() {
            // given
            val other = collabFixture.사진이_있는_세션()

            // when & then
            assertThatThrownBy {
                queryService.listPhotoComments(
                    shared.galleryId, other.session.sessionId, shared.photoId, shared.gallery.member.requiredId, 0, 50,
                )
            }.isInstanceOf(CollabException::class.java)
                .extracting("errorCode").isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
            assertThatThrownBy {
                queryService.listPhotoComments(
                    shared.galleryId, shared.session.sessionId, other.photoId, shared.gallery.member.requiredId, 0, 50,
                )
            }.isInstanceOf(CollabException::class.java)
                .extracting("errorCode").isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }

        @Test
        fun `같은 갤러리의 다른 컨셉과 미분류 사진도 읽을 수 없다`() {
            // given
            val other = collabFixture.사진이_있는_세션(shared.gallery)
            val unassigned = photoFixture.업로드된_사진(shared.galleryId, 1).single()

            // when & then
            listOf(other.photoId, unassigned).forEach { photoId ->
                assertThatThrownBy {
                    queryService.listPhotoComments(
                        shared.galleryId, shared.session.sessionId, photoId, shared.gallery.member.requiredId, 0, 50,
                    )
                }.isInstanceOf(CollabException::class.java)
                    .extracting("errorCode").isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
            }
        }

        @ParameterizedTest
        @EnumSource(TrashedResource::class)
        fun `휴지통의 부모나 사진은 조회할 수 없다`(resource: TrashedResource) {
            // given
            val now = ZonedDateTime.now()
            when (resource) {
                TrashedResource.GALLERY -> galleryRepository.saveAndFlush(
                    galleryRepository.findById(shared.galleryId).orElseThrow().also { it.moveToTrash(now) },
                )
                TrashedResource.SESSION -> sessionRepository.saveAndFlush(
                    sessionRepository.findById(shared.session.sessionId).orElseThrow().also { it.deletedAt = now },
                )
                TrashedResource.CONCEPT -> conceptRepository.saveAndFlush(
                    conceptRepository.findById(shared.session.conceptFolderId).orElseThrow().also { it.deletedAt = now },
                )
                TrashedResource.DETAIL -> detailRepository.saveAndFlush(
                    detailRepository.findById(shared.detailId).orElseThrow().also { it.deletedAt = now },
                )
                TrashedResource.PHOTO -> photoRepository.saveAndFlush(
                    photoRepository.findById(shared.photoId).orElseThrow().also { it.moveToTrash(now) },
                )
            }

            // when & then
            val failure = assertThatThrownBy {
                queryService.listPhotoComments(
                    shared.galleryId, shared.session.sessionId, shared.photoId, shared.gallery.member.requiredId, 0, 50,
                )
            }
            when (resource) {
                TrashedResource.GALLERY -> failure.isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_NOT_FOUND)
                TrashedResource.SESSION, TrashedResource.CONCEPT -> failure.isInstanceOf(CollabException::class.java)
                    .extracting("errorCode").isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
                TrashedResource.DETAIL, TrashedResource.PHOTO -> failure.isInstanceOf(CollabException::class.java)
                    .extracting("errorCode").isEqualTo(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
            }
        }
    }

    enum class TrashedResource { GALLERY, SESSION, CONCEPT, DETAIL, PHOTO }
}
