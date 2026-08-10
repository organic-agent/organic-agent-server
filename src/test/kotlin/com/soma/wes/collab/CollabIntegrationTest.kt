package com.soma.wes.collab

import com.jayway.jsonpath.JsonPath
import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabPhotoVoteRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.support.GuestTokenHeader
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoRating
import com.soma.wes.photo.repository.PhotoRatingRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 하객 협업 세션을 HTTP 경계에서 확인한다.
 *
 * 보는 것은 셋이다. **인증 없이 열어둔 문이 딱 그만큼만 열려 있는가**(폐기·마감·남의 세션이
 * 각각 막히는지), **하객 한 사람의 표가 하나로 유지되는가**, 그리고 **부부끼리의 정보가 하객
 * 화면으로 새지 않는가**(별점).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CollabIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val photoRepository: PhotoRepository,
    private val photoRatingRepository: PhotoRatingRepository,
    private val collabSessionRepository: CollabSessionRepository,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoVoteRepository: CollabPhotoVoteRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @BeforeEach
    fun clear() {
        // 자식부터 지운다. V11이 FK를 걸어두어 순서가 어긋나면 정리 자체가 실패한다.
        collabPhotoVoteRepository.deleteAllInBatch()
        collabPhotoCommentRepository.deleteAllInBatch()
        collabGuestRepository.deleteAllInBatch()
        collabPhotoRepository.deleteAllInBatch()
        collabSessionRepository.deleteAllInBatch()
        photoRatingRepository.deleteAllInBatch()
        photoRepository.deleteAllInBatch()
        galleryMemberRepository.deleteAllInBatch()
        galleryRepository.deleteAllInBatch()
        studioRepository.deleteAllInBatch()
    }

    // --- 세션 관리 ---

    @Test
    fun `부부가 세션을 열면 하객에게 보낼 링크가 온다`() {
        val fixture = openGalleryWithMember()

        mockMvc.post(sessionUrl(fixture)) { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.galleryId") { value(fixture.galleryId) }
                jsonPath("$.revoked") { value(false) }
                jsonPath("$.photoCount") { value(0) }
                // 초대 링크와 다른 화면으로 간다. 섞이면 하객이 로그인 화면을 만난다.
                jsonPath("$.shareUrl") { value(startsWith("http://localhost:3000/collab/")) }
                jsonPath("$.shareToken") { doesNotExist() }
            }
    }

    @Test
    fun `세션을 두 번 열어도 링크는 그대로다`() {
        // 버튼을 두 번 눌렀다고 하객이 들고 있는 링크가 죽으면 안 된다.
        val fixture = openGalleryWithMember()

        val first = openSession(fixture)
        val second = openSession(fixture)

        assertEquals(first, second)
        assertEquals(1, collabSessionRepository.findAll().size)
    }

    @Test
    fun `작가는 세션을 열 수 없다`() {
        // 하객에게 무엇을 물을지는 고르는 과정의 일부라 작가가 대신 정하지 않는다.
        val fixture = openGalleryWithMember()

        mockMvc.post(sessionUrl(fixture)) { authorize(fixture.photographer) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("GALLERY_403_1") }
            }
    }

    @Test
    fun `작가도 결과는 본다`() {
        val fixture = openGalleryWithMember()
        openSession(fixture)

        mockMvc.get(sessionUrl(fixture)) { authorize(fixture.photographer) }
            .andExpect {
                status { isOk() }
                jsonPath("$.shareUrl") { exists() }
            }
    }

    @Test
    fun `폐기하면 링크가 끊기고 다시 열면 새 토큰이 나온다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val guestToken = enter(shareToken, "친구")
        writeComment(shareToken, collabPhotoId, guestToken, "예쁘다")

        mockMvc.delete(sessionUrl(fixture)) { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/collab/$shareToken")
            .andExpect {
                // 우리가 발급한 링크가 맞으므로 404가 아니다.
                status { isGone() }
                jsonPath("$.code") { value("COLLAB_410_1") }
            }

        val reopened = openSession(fixture)
        assertNotEquals(shareToken, reopened, "폐기한 토큰을 되살리면 링크가 퍼진 단톡방이 함께 되살아난다")

        // 끊은 것은 링크이지 하객이 남겨준 말이 아니다.
        mockMvc.get("/api/v1/collab/$reopened/photos/$collabPhotoId/comments")
            .andExpect {
                status { isOk() }
                jsonPath("$.comments") { value(hasSize<Any>(1)) }
            }
    }

    // --- 사진 담기 ---

    @Test
    fun `부부가 담은 사진만 하객에게 보인다`() {
        val fixture = openGalleryWithMember()
        val photoIds = savePhotos(fixture, count = 3)
        val shareToken = openSession(fixture)

        addPhotos(fixture, photoIds.take(2))

        mockMvc.get("/api/v1/collab/$shareToken/photos")
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(2) }
                // 버킷이 비공개라 서명 URL 없이는 아무것도 띄울 수 없다.
                jsonPath("$.photos[0].photo.viewUrl") { value(containsString("X-Amz-Signature")) }
                jsonPath("$.photos[0].reactions.good") { value(0) }
                jsonPath("$.photos[0].commentCount") { value(0) }
            }
    }

    @Test
    fun `이미 담긴 사진이 섞이면 요청 전체가 거절된다`() {
        // 일부만 담아두면 화면에는 성공으로 보이고 어느 사진이 빠졌는지 아무도 모른다.
        val fixture = openGalleryWithMember()
        val photoIds = savePhotos(fixture, count = 2)
        openSession(fixture)
        addPhotos(fixture, photoIds.take(1))

        mockMvc.post("${sessionUrl(fixture)}/photos") {
            authorize(fixture.member)
            jsonBody("""{"photoIds":$photoIds}""")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("COLLAB_409_1") }
        }

        assertEquals(1, collabPhotoRepository.findAll().size)
    }

    @Test
    fun `다른 갤러리의 사진은 담을 수 없다`() {
        // 갤러리 권한만 보고 id를 믿으면 자기 세션으로 남의 사진 서명 URL을 하객에게 내보낸다.
        val fixture = openGalleryWithMember()
        val other = openGalleryWithMember()
        openSession(fixture)
        val strangerPhotoId = savePhotos(other, count = 1).single()

        mockMvc.post("${sessionUrl(fixture)}/photos") {
            authorize(fixture.member)
            jsonBody("""{"photoIds":[$strangerPhotoId]}""")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("COLLAB_400_3") }
        }
    }

    @Test
    fun `아직 올라오지 않은 사진은 담을 수 없다`() {
        val fixture = openGalleryWithMember()
        openSession(fixture)
        val pendingId = savePhotos(fixture, count = 1, uploaded = false).single()

        mockMvc.post("${sessionUrl(fixture)}/photos") {
            authorize(fixture.member)
            jsonBody("""{"photoIds":[$pendingId]}""")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("COLLAB_400_4") }
        }
    }

    @Test
    fun `사진을 빼면 거기 달린 의견도 함께 사라진다`() {
        // 세션에서 뺀다는 것은 "이 사진은 더 묻지 않겠다"는 뜻이다.
        val fixture = openGalleryWithMember()
        val photoId = savePhotos(fixture, count = 1).single()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, listOf(photoId)).single()
        val guestToken = enter(shareToken, "친구")
        writeComment(shareToken, collabPhotoId, guestToken, "이거 좋다")
        vote(shareToken, collabPhotoId, guestToken, "GOOD")

        mockMvc.delete("${sessionUrl(fixture)}/photos") {
            authorize(fixture.member)
            jsonBody("""{"photoIds":[$photoId]}""")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(0) }
        }

        assertEquals(0, collabPhotoCommentRepository.findAll().size)
        assertEquals(0, collabPhotoVoteRepository.findAll().size)
    }

    // --- 하객 ---

    @Test
    fun `로그인하지 않아도 링크만으로 열린다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        addPhotos(fixture, savePhotos(fixture, count = 2))

        mockMvc.get("/api/v1/collab/$shareToken")
            .andExpect {
                status { isOk() }
                jsonPath("$.galleryTitle") { value("본식") }
                jsonPath("$.photoCount") { value(2) }
                jsonPath("$.writable") { value(true) }
                // 링크를 주운 사람에게까지 알려줄 이유가 없는 값들이다.
                jsonPath("$.galleryId") { doesNotExist() }
                jsonPath("$.selectionDeadline") { doesNotExist() }
            }
    }

    @Test
    fun `발급한 적 없는 토큰은 404`() {
        mockMvc.get("/api/v1/collab/never-issued")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("COLLAB_404_1") }
            }
    }

    @Test
    fun `하객에게는 부부가 매긴 별점이 보이지 않는다`() {
        // 별점은 부부와 작가가 고르며 서로에게 남기는 표시다. 사진에 찍힌 하객이 자기 사진의
        // 점수를 보게 되는 일까지 생긴다.
        val fixture = openGalleryWithMember()
        val photoId = savePhotos(fixture, count = 1).single()
        photoRatingRepository.save(PhotoRating.of(photoId = photoId, score = 2, ratedBy = fixture.member.id!!))
        val shareToken = openSession(fixture)
        addPhotos(fixture, listOf(photoId))

        mockMvc.get("/api/v1/collab/$shareToken/photos")
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].photo.score") { doesNotExist() }
            }

        // 같은 사진을 부부가 갤러리 그리드에서 보면 그대로 보인다.
        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].score") { value(2) }
            }
    }

    @Test
    fun `닉네임을 적으면 하객 토큰이 발급된다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)

        mockMvc.post("/api/v1/collab/$shareToken/guests") { jsonBody("""{"nickname":"신부 친구 영희"}""") }
            .andExpect {
                status { isCreated() }
                jsonPath("$.nickname") { value("신부 친구 영희") }
                jsonPath("$.guestToken") { exists() }
            }
    }

    @Test
    fun `닉네임이 비면 입장할 수 없다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)

        mockMvc.post("/api/v1/collab/$shareToken/guests") { jsonBody("""{"nickname":"   "}""") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("COLLAB_400_1") }
            }
    }

    // --- 댓글 ---

    @Test
    fun `하객이 댓글을 남기면 목록에 자기 것으로 표시된다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val mine = enter(shareToken, "영희")
        val others = enter(shareToken, "철수")
        writeComment(shareToken, collabPhotoId, mine, "이 표정이 제일 신부님답네요")
        writeComment(shareToken, collabPhotoId, others, "저는 옆 사진이 더 좋아요")

        mockMvc.get("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments") { guest(mine) }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(2) }
                // 최근 것이 위로 온다.
                jsonPath("$.comments[0].nickname") { value("철수") }
                jsonPath("$.comments[0].mine") { value(false) }
                jsonPath("$.comments[1].nickname") { value("영희") }
                jsonPath("$.comments[1].mine") { value(true) }
            }
    }

    @Test
    fun `토큰 없이는 댓글을 남길 수 없다`() {
        // 보는 것은 토큰 없이 되고, 남기는 것만 "당신이 누구인지"를 먼저 묻는다.
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()

        mockMvc.post("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments") {
            jsonBody("""{"content":"익명으로 한마디"}""")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("COLLAB_401_1") }
        }
    }

    @Test
    fun `다른 세션에서 받은 토큰으로는 글을 남길 수 없다`() {
        // 한 하객이 여러 결혼식 링크를 받을 수 있다. 토큰만 보면 A에서 받은 것으로 B에 쓴다.
        val fixture = openGalleryWithMember()
        val other = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val otherToken = openSession(other)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val otherGuest = enter(otherToken, "옆 결혼식 하객")

        mockMvc.post("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments") {
            guest(otherGuest)
            jsonBody("""{"content":"여긴 어디"}""")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("COLLAB_401_1") }
        }
    }

    @Test
    fun `하객은 자기 댓글만 지운다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val mine = enter(shareToken, "영희")
        val others = enter(shareToken, "철수")
        val commentId = writeComment(shareToken, collabPhotoId, mine, "지울 댓글")

        mockMvc.delete("/api/v1/collab/$shareToken/comments/$commentId") { guest(others) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("COLLAB_403_3") }
            }

        mockMvc.delete("/api/v1/collab/$shareToken/comments/$commentId") { guest(mine) }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `부부는 하객이 쓴 댓글을 지울 수 있다`() {
        // 하객 토큰은 브라우저에 저장된 값이라 그것 하나로 남의 글을 지우게 둘 수 없다.
        // 부적절한 말을 치우는 것은 로그인한 부부·작가의 몫이다.
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val guestToken = enter(shareToken, "누군가")
        val commentId = writeComment(shareToken, collabPhotoId, guestToken, "불편한 말")

        mockMvc.delete("${sessionUrl(fixture)}/comments/$commentId") { authorize(fixture.member) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, collabPhotoCommentRepository.findAll().size)
    }

    // --- 반응 ---

    @Test
    fun `같은 하객이 여러 번 눌러도 표는 하나다`() {
        // 새로고침할 때마다 표가 쌓이면 "좋아요 40"이 사람 40명이 아니게 된다.
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val guestToken = enter(shareToken, "친구")

        repeat(3) { vote(shareToken, collabPhotoId, guestToken, "GOOD") }
        vote(shareToken, collabPhotoId, guestToken, "SOSO")

        assertEquals(1, collabPhotoVoteRepository.findAll().size)
        mockMvc.get("/api/v1/collab/$shareToken/photos") { guest(guestToken) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].reactions.good") { value(0) }
                jsonPath("$.photos[0].reactions.soso") { value(1) }
                jsonPath("$.photos[0].myReaction") { value("SOSO") }
            }
    }

    @Test
    fun `하객마다 한 표씩 쌓이고 부부는 그 수를 본다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        vote(shareToken, collabPhotoId, enter(shareToken, "하객1"), "GOOD")
        vote(shareToken, collabPhotoId, enter(shareToken, "하객2"), "GOOD")
        vote(shareToken, collabPhotoId, enter(shareToken, "하객3"), "BAD")

        mockMvc.get("${sessionUrl(fixture)}/photos") { authorize(fixture.member) }
            .andExpect {
                status { isOk() }
                jsonPath("$.photos[0].reactions.good") { value(2) }
                jsonPath("$.photos[0].reactions.bad") { value(1) }
                // 부부와 작가는 하객이 아니라 반응을 남기지 않는다.
                jsonPath("$.photos[0].myReaction") { doesNotExist() }
            }
    }

    @Test
    fun `반응은 취소할 수 있고 누른 적 없어도 성공한다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val guestToken = enter(shareToken, "친구")

        mockMvc.delete("/api/v1/collab/$shareToken/photos/$collabPhotoId/vote") { guest(guestToken) }
            .andExpect { status { isNoContent() } }

        vote(shareToken, collabPhotoId, guestToken, "GOOD")
        mockMvc.delete("/api/v1/collab/$shareToken/photos/$collabPhotoId/vote") { guest(guestToken) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, collabPhotoVoteRepository.findAll().size)
    }

    @Test
    fun `남의 세션 사진에는 반응할 수 없다`() {
        // 링크는 갤러리마다 다르지만 협업 사진 id는 전역에서 이어지는 값이다.
        val fixture = openGalleryWithMember()
        val other = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val otherShareToken = openSession(other)
        addPhotos(fixture, savePhotos(fixture, count = 1))
        val othersCollabPhotoId = addPhotos(other, savePhotos(other, count = 1)).single()
        val guestToken = enter(shareToken, "친구")

        mockMvc.put("/api/v1/collab/$shareToken/photos/$othersCollabPhotoId/vote") {
            guest(guestToken)
            jsonBody("""{"reaction":"GOOD"}""")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("COLLAB_404_2") }
        }

        // 다른 세션에서는 정상적으로 눌린다 — id 자체가 없는 것이 아니다.
        assertEquals(1, collabPhotoRepository.findAllByCollabSessionId(
            collabSessionRepository.findByShareToken(otherShareToken)!!.requiredId,
        ).size)
    }

    // --- 마감 ---

    @Test
    fun `마감된 뒤에는 보기만 되고 남길 수는 없다`() {
        val fixture = openGalleryWithMember()
        val shareToken = openSession(fixture)
        val collabPhotoId = addPhotos(fixture, savePhotos(fixture, count = 1)).single()
        val guestToken = enter(shareToken, "친구")
        writeComment(shareToken, collabPhotoId, guestToken, "마감 전에 남긴 말")
        passDeadline(fixture)

        mockMvc.get("/api/v1/collab/$shareToken")
            .andExpect {
                status { isOk() }
                // 화면은 이 값을 보고 댓글창을 감춘다. 프론트가 마감 시각으로 따로 계산하면
                // 서버가 막는 기준과 어긋나는 날이 온다.
                jsonPath("$.writable") { value(false) }
            }
        mockMvc.get("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments")
            .andExpect {
                status { isOk() }
                jsonPath("$.totalCount") { value(1) }
            }

        mockMvc.post("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments") {
            guest(guestToken)
            jsonBody("""{"content":"늦게 온 말"}""")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("COLLAB_403_2") }
        }
        mockMvc.put("/api/v1/collab/$shareToken/photos/$collabPhotoId/vote") {
            guest(guestToken)
            jsonBody("""{"reaction":"GOOD"}""")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `공개 경로가 열려도 갤러리 API는 그대로 막혀 있다`() {
        val fixture = openGalleryWithMember()
        openSession(fixture)

        mockMvc.get("/api/v1/galleries/${fixture.galleryId}/photos")
            .andExpect { status { isUnauthorized() } }
        mockMvc.get(sessionUrl(fixture))
            .andExpect { status { isUnauthorized() } }
        mockMvc.post(sessionUrl(fixture))
            .andExpect { status { isUnauthorized() } }
    }

    // --- helpers ---

    private data class Fixture(val photographer: User, val member: User, val galleryId: Long)

    private fun sessionUrl(fixture: Fixture) = "/api/v1/galleries/${fixture.galleryId}/collab-session"

    /** 협업 세션은 열린 갤러리의 부부가 여는 것이라, 매번 열린 갤러리와 멤버가 필요하다. */
    private fun openGalleryWithMember(): Fixture {
        val photographer = signUpUser()
        val suffix = sequence.incrementAndGet()
        val studio = studioRepository.save(
            Studio(userId = photographer.id!!, name = "테스트 스튜디오", galleryUrl = "studio-$suffix"),
        )
        val gallery = galleryRepository.save(
            Gallery(studioId = studio.id!!, title = "본식", status = GalleryStatus.OPEN),
        )

        val member = signUpUser()
        galleryMemberRepository.save(GalleryMember(galleryId = gallery.id!!, userId = member.id!!))

        return Fixture(photographer, member, gallery.id!!)
    }

    private fun signUpUser(): User {
        val suffix = sequence.incrementAndGet()
        return userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "collab-$suffix",
                nickname = "테스터",
                email = "collab-$suffix@example.com",
            ),
        )
    }

    private fun savePhotos(fixture: Fixture, count: Int, uploaded: Boolean = true): List<Long> =
        (1..count).map { index ->
            val photo = Photo(
                galleryId = fixture.galleryId,
                storageKey = "galleries/${fixture.galleryId}/${sequence.incrementAndGet()}.jpg",
                originalFileName = "$index.jpg",
                contentType = "image/jpeg",
                displayOrder = index,
            )
            if (uploaded) {
                photo.markUploaded()
            }
            photoRepository.save(photo).requiredId
        }

    /**
     * 마감을 과거로 밀어 하객의 쓰기를 잠근다.
     *
     * `changeSelectionDeadline`을 쓰지 않는다 — 지난 기한은 그쪽에서 막힌다. 여기서 필요한 것은
     * 시간이 흘러 기한이 지나버린 **상태**라 필드를 직접 세운다.
     */
    private fun passDeadline(fixture: Fixture) {
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.selectionDeadline = ZonedDateTime.now().minusDays(1)
        galleryRepository.saveAndFlush(gallery)
    }

    /** 응답에는 토큰이 없다 — 조립이 끝난 링크만 준다. 하객 경로를 부르려면 거기서 떼어낸다. */
    private fun openSession(fixture: Fixture): String {
        val response = mockMvc.post(sessionUrl(fixture)) { authorize(fixture.member) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return JsonPath.read<String>(response, "$.shareUrl").substringAfterLast('/')
    }

    private fun addPhotos(fixture: Fixture, photoIds: List<Long>): List<Long> {
        val response = mockMvc.post("${sessionUrl(fixture)}/photos") {
            authorize(fixture.member)
            jsonBody("""{"photoIds":$photoIds}""")
        }.andExpect { status { isOk() } }
            .andReturn().response.contentAsString

        return JsonPath.read<List<Int>>(response, "$.photos[*].collabPhotoId").map { it.toLong() }
    }

    private fun enter(shareToken: String, nickname: String): String {
        val response = mockMvc.post("/api/v1/collab/$shareToken/guests") {
            jsonBody("""{"nickname":"$nickname"}""")
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read(response, "$.guestToken")
    }

    private fun writeComment(shareToken: String, collabPhotoId: Long, guestToken: String, content: String): Long {
        val response = mockMvc.post("/api/v1/collab/$shareToken/photos/$collabPhotoId/comments") {
            guest(guestToken)
            jsonBody("""{"content":"$content"}""")
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        return JsonPath.read<Int>(response, "$.commentId").toLong()
    }

    private fun vote(shareToken: String, collabPhotoId: Long, guestToken: String, reaction: String) {
        mockMvc.put("/api/v1/collab/$shareToken/photos/$collabPhotoId/vote") {
            guest(guestToken)
            jsonBody("""{"reaction":"$reaction"}""")
        }.andExpect { status { isNoContent() } }
    }

    private fun MockHttpServletRequestDsl.authorize(user: User) {
        header("Authorization", "Bearer ${authTokenProvider.generateAccessToken(user).value}")
    }

    private fun MockHttpServletRequestDsl.guest(guestToken: String) {
        header(GuestTokenHeader.GUEST_TOKEN, guestToken)
    }

    private fun MockHttpServletRequestDsl.jsonBody(body: String) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
