package com.soma.wes.security

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.collab.support.GuestTokenHeader
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.service.PhotoCommentService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** JWT 인증과 하객 토큰의 경계는 서비스 직접 호출로 검증할 수 없어 HTTP 회귀 가드로 둔다. */
@IntegrationTest
class PhotoCommentAuthenticationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val userFixture: UserFixture,
    private val categoryService: CategoryService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val photoCommentService: PhotoCommentService,
) {
    private lateinit var gallery: OpenGallery
    private var photoId: Long = 0
    private lateinit var guestToken: String
    private val commentsPath get() = "/api/v1/galleries/${gallery.galleryId}/photos/$photoId/comments"

    @BeforeEach
    fun setUpBaseData() {
        gallery = galleryFixture.멤버와_열린_갤러리()
        photoId = photoFixture.업로드된_사진(gallery.galleryId, 1).single()
        val managerId = gallery.photographer.requiredId
        val concept = categoryService.createConcept(
            gallery.galleryId, managerId, CreateConceptFolderRequest("공유 컨셉"),
        )
        val detail = categoryService.createDetail(
            gallery.galleryId, concept.id, managerId, CreateDetailFolderRequest("공유 사진"),
        )
        categoryService.movePhotos(
            gallery.galleryId, managerId, MoveCategoryPhotosRequest(listOf(photoId), detail.id),
        )
        val session = collabSessionService.open(
            gallery.galleryId, managerId, OpenCollabSessionRequest(conceptFolderId = concept.id, name = "친구 의견"),
        )
        guestToken = collabGuestService.enter(
            session.collabUrl.substringAfterLast('/'), EnterCollabRequest("하객"),
        ).guestToken
    }

    @Test
    fun `JWT가 없으면 유효한 하객 토큰으로도 내부 댓글 조회 작성 삭제를 할 수 없다`() {
        // given
        val existing = photoCommentService.write(
            gallery.galleryId, photoId, gallery.member.requiredId, WritePhotoCommentRequest("부부 대화"),
        )

        // when & then
        listOf(null, guestToken).forEach { token ->
            mockMvc.get(commentsPath) {
                if (token != null) header(GuestTokenHeader.NAME, token)
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHZ_401_1") }
            }
            mockMvc.post(commentsPath) {
                if (token != null) header(GuestTokenHeader.NAME, token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"하객의 내부 댓글 시도"}"""
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHZ_401_1") }
            }
            mockMvc.delete("$commentsPath/${existing.commentId}") {
                if (token != null) header(GuestTokenHeader.NAME, token)
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("AUTHZ_401_1") }
            }
        }
        assertThat(photoCommentService.list(gallery.galleryId, photoId, gallery.member.requiredId, 0, 20).contents)
            .extracting("commentId")
            .containsExactly(existing.commentId)
    }

    @Test
    fun `요청 본문의 작성자 정보를 위조해도 JWT 사용자 이름과 소유권으로 저장된다`() {
        // given
        val other = userFixture.사용자(nickname = "다른 사용자")
        val accessToken = authTokenProvider.generateAccessToken(gallery.member).value

        // when & then
        mockMvc.post(commentsPath) {
            header("Authorization", "Bearer $accessToken")
            header(GuestTokenHeader.NAME, guestToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "content":"이 사진이 좋아",
                "authorId":${other.requiredId},
                "userId":${other.requiredId},
                "nickname":"위조한 이름",
                "mine":false
            }"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.authorId") { value(gallery.member.requiredId) }
            jsonPath("$.nickname") { value(gallery.member.nickname) }
            jsonPath("$.content") { value("이 사진이 좋아") }
            jsonPath("$.mine") { value(true) }
        }
        mockMvc.get(commentsPath) {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.contents.length()") { value(1) }
            jsonPath("$.contents[0].authorId") { value(gallery.member.requiredId) }
            jsonPath("$.contents[0].nickname") { value(gallery.member.nickname) }
            jsonPath("$.contents[0].mine") { value(true) }
        }
    }

    @Test
    fun `갤러리와 무관한 JWT에 유효한 하객 토큰을 더해도 내부 댓글 권한이 생기지 않는다`() {
        // given
        val outsider = userFixture.사용자()
        val accessToken = authTokenProvider.generateAccessToken(outsider).value
        val existing = photoCommentService.write(
            gallery.galleryId, photoId, gallery.member.requiredId, WritePhotoCommentRequest("부부 대화"),
        )

        // when & then
        mockMvc.get(commentsPath) {
            header("Authorization", "Bearer $accessToken")
            header(GuestTokenHeader.NAME, guestToken)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }
        mockMvc.post(commentsPath) {
            header("Authorization", "Bearer $accessToken")
            header(GuestTokenHeader.NAME, guestToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"외부 사용자의 내부 댓글 시도"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }
        mockMvc.delete("$commentsPath/${existing.commentId}") {
            header("Authorization", "Bearer $accessToken")
            header(GuestTokenHeader.NAME, guestToken)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("GALLERY_403_1") }
        }
        assertThat(photoCommentService.list(gallery.galleryId, photoId, gallery.member.requiredId, 0, 20).contents)
            .extracting("commentId")
            .containsExactly(existing.commentId)
    }
}
