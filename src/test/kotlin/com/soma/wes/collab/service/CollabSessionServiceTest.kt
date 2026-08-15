package com.soma.wes.collab.service

import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.folder.fixture.FolderFixture
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 부부 쪽 협업 세션 관리를 서비스 경계에서 확인한다.
 *
 * 보는 것은 셋이다. **링크가 세션 단위로 갈라지는가**(두 번 열면 따로, 폴더는 참조가 아니라 복사),
 * **링크의 수명이 의도대로 움직이는가**(이름 변경은 링크를 살려 두고, 폐기·재발급은 토큰만 간다),
 * 그리고 **누가 무엇을 할 수 있는가**(여는 것은 부부, 결과는 작가도 본다).
 */
@IntegrationTest
class CollabSessionServiceTest @Autowired constructor(
    private val collabSessionService: CollabSessionService,
    private val collabSessionQueryService: CollabSessionQueryService,
    private val collabGuestService: CollabGuestService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val folderFixture: FolderFixture,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoLikeRepository: CollabPhotoLikeRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }

    @Nested
    @DisplayName("세션을 관리할 때")
    inner class SessionManagement {

        @Test
        fun `부부가 세션을 열면 하객에게 보낼 링크가 온다`() {
            // when
            val result = collabSessionService.open(
                fixture.galleryId, fixture.member.id!!, OpenCollabSessionRequest(name = "부모님께"),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(result.galleryId).isEqualTo(fixture.galleryId)
                softly.assertThat(result.name).isEqualTo("부모님께")
                softly.assertThat(result.revoked).isFalse()
                softly.assertThat(result.photoCount).isEqualTo(0L)
                // 초대 링크와 다른 화면으로 간다. 섞이면 하객이 로그인 화면을 만난다.
                softly.assertThat(result.collabUrl).startsWith("http://localhost:3000/collab/")
            }
        }

        @Test
        fun `세션을 두 번 열면 링크가 따로 생긴다`() {
            // 부부는 묶음마다 물어볼 상대가 다르다. 하나로 묶으면 돌아온 의견도 갈라지지 않는다.
            // given
            val first = openSession(fixture, name = "부모님께")
            val second = openSession(fixture, name = "친구들에게")

            // when
            val sessions = collabSessionQueryService.list(fixture.galleryId, fixture.member.id!!)

            // then
            assertThat(second.collabToken).isNotEqualTo(first.collabToken)
            assertSoftly { softly ->
                softly.assertThat(sessions).hasSize(2)
                // 최근에 만든 것이 위로 온다.
                softly.assertThat(sessions[0].name).isEqualTo("친구들에게")
                softly.assertThat(sessions[1].name).isEqualTo("부모님께")
            }
        }

        @Test
        fun `이름이 비면 세션을 열 수 없다`() {
            // 링크가 여러 개인 순간 "어느 링크였더라"가 생긴다. 토큰은 사람이 알아볼 값이 아니다.
            // when & then
            assertThatThrownBy { openSession(fixture, name = "   ") }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.INVALID_SESSION_NAME)

            assertThat(collabSessionRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `이름은 링크를 죽이지 않고 바꾼다`() {
            // given
            val session = openSession(fixture, name = "오타난이름")

            // when
            val renamed = collabSessionService.rename(
                fixture.galleryId, session.sessionId, fixture.member.id!!, RenameCollabSessionRequest(name = "부모님께"),
            )

            // then
            assertThat(renamed.name).isEqualTo("부모님께")
            // 하객이 들고 있는 주소가 이름 때문에 죽으면 안 된다.
            assertThat(collabGuestQueryService.getLanding(session.collabToken).galleryTitle).isEqualTo("본식")
        }

        @Test
        fun `작가는 세션을 열 수 없다`() {
            // 하객에게 무엇을 물을지는 고르는 과정의 일부라 작가가 대신 정하지 않는다.
            // when & then
            assertThatThrownBy {
                collabSessionService.open(
                    fixture.galleryId, fixture.photographer.id!!, OpenCollabSessionRequest(name = "작가가 여는 링크"),
                )
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        @Test
        fun `작가도 결과는 본다`() {
            // given
            val session = openSession(fixture)

            // when
            val result = collabSessionQueryService.get(fixture.galleryId, session.sessionId, fixture.photographer.id!!)

            // then
            assertThat(result.collabUrl).isNotBlank()
        }

        @Test
        fun `남의 갤러리 세션은 내 갤러리 권한으로 열리지 않는다`() {
            // 인가는 갤러리 단위다. 세션을 id로만 찾으면 자기 부부 권한으로 남의 링크를 읽는다.
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val othersSession = openSession(other)

            // when & then
            assertThatThrownBy {
                collabSessionQueryService.get(fixture.galleryId, othersSession.sessionId, fixture.member.id!!)
            }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
        }

        @Test
        fun `폐기하면 링크가 끊기고 재발급하면 새 토큰이 나온다`() {
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "친구")
            writeComment(session.collabToken, collabPhotoId, guestToken, "예쁘다")

            // when & then
            collabSessionService.revoke(fixture.galleryId, session.sessionId, fixture.member.id!!)

            assertThatThrownBy { collabGuestQueryService.getLanding(session.collabToken) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.SESSION_REVOKED)

            val republished = collabSessionService.republish(fixture.galleryId, session.sessionId, fixture.member.id!!)
            assertThat(republished.revoked).isFalse()
            assertThat(republished.collabToken)
                .describedAs("폐기한 토큰을 되살리면 링크가 퍼진 단톡방이 함께 되살아난다")
                .isNotEqualTo(session.collabToken)

            // 세션을 새로 열지 않고 토큰만 갈았으므로, 받은 말은 그대로 남는다.
            val comments = collabGuestQueryService.listComments(
                republished.collabToken, collabPhotoId, guestToken = null, page = 0, size = 20,
            )
            assertThat(comments.totalCount).isEqualTo(1L)
            assertThat(collabSessionRepository.count()).isEqualTo(1L)
        }
    }

    @Nested
    @DisplayName("폴더로 세션을 열 때")
    inner class OpenFromFolder {

        @Test
        fun `폴더로 열면 그 폴더의 사진이 그대로 담긴다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val folderId = folderFixture.확정된_폴더(fixture.galleryId, "본식 후보", photoIds.take(2))

            // when
            val session = openSession(fixture, name = "본식 후보", folderId = folderId)

            // then
            // 폴더에 없던 세 번째 사진은 오지 않는다.
            assertThat(session.photoCount).isEqualTo(2L)
            val guestView = collabGuestQueryService.listPhotos(session.collabToken, guestToken = null, page = 0, size = 20)
            assertThat(guestView.totalCount).isEqualTo(2L)
        }

        @Test
        fun `폴더를 고쳐도 이미 연 세션은 흔들리지 않는다`() {
            // 참조가 아니라 복사다. 가리키게 두면 하객이 보던 사진이 발밑에서 바뀌고,
            // 이미 받은 댓글이 어느 사진에 달린 것인지 알 수 없게 된다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val folderId = folderFixture.확정된_폴더(fixture.galleryId, "본식 후보", photoIds)
            val session = openSession(fixture, name = "본식 후보", folderId = folderId)

            // when
            photoFolderItemRepository.deleteAllInBatch(photoFolderItemRepository.findAllByFolderId(folderId))
            photoFolderRepository.deleteById(folderId)

            // then
            val guestView = collabGuestQueryService.listPhotos(session.collabToken, guestToken = null, page = 0, size = 20)
            assertThat(guestView.totalCount).isEqualTo(2L)
        }

        @Test
        fun `빈 폴더로는 세션을 열 수 없다`() {
            // 아무것도 담기지 않은 링크를 성공으로 돌려주면 부부는 그것을 그대로 하객에게 보낸다.
            // given
            val folderId = folderFixture.확정된_폴더(fixture.galleryId, "비어 있는 묶음", emptyList())

            // when & then
            assertThatThrownBy { openSession(fixture, name = "빈 링크", folderId = folderId) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.EMPTY_FOLDER)

            assertThat(collabSessionRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `다른 갤러리의 폴더로는 세션을 열 수 없다`() {
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val othersFolderId = folderFixture.확정된_폴더(
                other.galleryId, "남의 묶음", photoFixture.업로드된_사진(other.galleryId, count = 1),
            )

            // when & then
            assertThatThrownBy { openSession(fixture, name = "남의 폴더로", folderId = othersFolderId) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.FOLDER_NOT_IN_GALLERY)

            // 폴더가 잘못됐는데 세션만 남으면, 실패로 보이는 요청이 빈 링크를 하나 남긴다.
            assertThat(collabSessionRepository.count()).isEqualTo(0L)
        }

        @Test
        fun `링크마다 자기 사진과 자기 의견만 보인다`() {
            // 같은 사진을 부모님께도 친구들에게도 물을 수 있고, 그때 두 쪽의 의견은 따로 모인다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val parents = openSession(
                fixture,
                name = "부모님께",
                folderId = folderFixture.확정된_폴더(fixture.galleryId, "본식 후보", photoIds.take(2)),
            )
            val friends = openSession(
                fixture,
                name = "친구들에게",
                folderId = folderFixture.확정된_폴더(fixture.galleryId, "2부 사진", photoIds.drop(2)),
            )

            // when & then
            assertThat(collabGuestQueryService.listPhotos(parents.collabToken, guestToken = null, page = 0, size = 20).totalCount)
                .isEqualTo(2L)
            assertThat(collabGuestQueryService.listPhotos(friends.collabToken, guestToken = null, page = 0, size = 20).totalCount)
                .isEqualTo(1L)

            // 한쪽에 남긴 좋아요가 다른 쪽 집계에 섞이지 않는다.
            val parentsPhotoId = collabPhotoRepository
                .findAllByCollabSessionId(parents.sessionId)
                .first()
                .requiredId
            collabGuestService.like(parents.collabToken, parentsPhotoId, enter(parents.collabToken, "어머니"))

            val friendsView = collabSessionQueryService.listPhotos(
                fixture.galleryId, friends.sessionId, fixture.member.id!!, page = 0, size = 20,
            )
            assertThat(friendsView.contents[0].likeCount).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("사진을 담을 때")
    inner class AddPhotos {

        @Test
        fun `부부가 담은 사진만 하객에게 보인다`() {
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 3)
            val session = openSession(fixture)

            // when
            addPhotos(fixture, session.sessionId, photoIds.take(2))

            // then
            val guestView = collabGuestQueryService.listPhotos(session.collabToken, guestToken = null, page = 0, size = 20)
            assertSoftly { softly ->
                softly.assertThat(guestView.totalCount).isEqualTo(2L)
                // 버킷이 비공개라 서명 URL 없이는 아무것도 띄울 수 없다.
                softly.assertThat(guestView.contents[0].photo.viewUrl).contains("X-Amz-Signature")
                softly.assertThat(guestView.contents[0].likeCount).isEqualTo(0L)
                softly.assertThat(guestView.contents[0].commentCount).isEqualTo(0L)
            }
        }

        @Test
        fun `이미 담긴 사진이 섞이면 요청 전체가 거절된다`() {
            // 일부만 담아두면 화면에는 성공으로 보이고 어느 사진이 빠졌는지 아무도 모른다.
            // given
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, count = 2)
            val session = openSession(fixture)
            addPhotos(fixture, session.sessionId, photoIds.take(1))

            // when & then
            assertThatThrownBy { addPhotos(fixture, session.sessionId, photoIds) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_ALREADY_ADDED)

            assertThat(collabPhotoRepository.count()).isEqualTo(1L)
        }

        @Test
        fun `다른 갤러리의 사진은 담을 수 없다`() {
            // 갤러리 권한만 보고 id를 믿으면 자기 세션으로 남의 사진 서명 URL을 하객에게 내보낸다.
            // given
            val other = galleryFixture.멤버와_열린_갤러리()
            val session = openSession(fixture)
            val strangerPhotoId = photoFixture.업로드된_사진(other.galleryId, count = 1).single()

            // when & then
            assertThatThrownBy { addPhotos(fixture, session.sessionId, listOf(strangerPhotoId)) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_NOT_IN_GALLERY)
        }

        @Test
        fun `아직 올라오지 않은 사진은 담을 수 없다`() {
            // given
            val session = openSession(fixture)
            val pendingId = photoFixture.대기중_사진(fixture.galleryId, count = 1).single()

            // when & then
            assertThatThrownBy { addPhotos(fixture, session.sessionId, listOf(pendingId)) }
                .isInstanceOf(CollabException::class.java)
                .extracting("errorCode")
                .isEqualTo(CollabErrorCode.PHOTO_NOT_UPLOADED)
        }

        @Test
        fun `사진을 빼면 거기 달린 의견도 함께 사라진다`() {
            // 세션에서 뺀다는 것은 "이 사진은 더 묻지 않겠다"는 뜻이다.
            // given
            val photoId = photoFixture.업로드된_사진(fixture.galleryId, count = 1).single()
            val session = openSession(fixture)
            val collabPhotoId = addPhotos(fixture, session.sessionId, listOf(photoId)).single()
            val guestToken = enter(session.collabToken, "친구")
            writeComment(session.collabToken, collabPhotoId, guestToken, "이거 좋다")
            collabGuestService.like(session.collabToken, collabPhotoId, guestToken)

            // when
            val result = collabSessionService.removePhotos(
                fixture.galleryId, session.sessionId, fixture.member.id!!, RemoveCollabPhotosRequest(listOf(photoId)),
            )

            // then
            assertThat(result.totalCount).isEqualTo(0L)
            assertThat(collabPhotoCommentRepository.count()).isEqualTo(0L)
            assertThat(collabPhotoLikeRepository.count()).isEqualTo(0L)
        }
    }

    @Nested
    @DisplayName("댓글을 지울 때")
    inner class DeleteComment {

        @Test
        fun `부부는 하객이 쓴 댓글을 지울 수 있다`() {
            // 하객 토큰은 브라우저에 저장된 값이라 그것 하나로 남의 글을 지우게 둘 수 없다.
            // 부적절한 말을 치우는 것은 로그인한 부부·작가의 몫이다.
            // given
            val session = openSession(fixture)
            val collabPhotoId =
                addPhotos(fixture, session.sessionId, photoFixture.업로드된_사진(fixture.galleryId, count = 1)).single()
            val guestToken = enter(session.collabToken, "누군가")
            val commentId = writeComment(session.collabToken, collabPhotoId, guestToken, "불편한 말")

            // when
            collabSessionService.deleteComment(fixture.galleryId, session.sessionId, commentId, fixture.member.id!!)

            // then
            assertThat(collabPhotoCommentRepository.count()).isEqualTo(0L)
        }
    }

    // --- helpers ---

    /** 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 하객 쪽을 부르려면 거기서 떼어낸다. */
    private val CollabSessionResponse.collabToken: String
        get() = collabUrl.substringAfterLast('/')

    private fun openSession(fixture: OpenGallery, name: String = "하객에게", folderId: Long? = null): CollabSessionResponse =
        collabSessionService.open(
            fixture.galleryId, fixture.member.id!!, OpenCollabSessionRequest(name = name, folderId = folderId),
        )

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
}
