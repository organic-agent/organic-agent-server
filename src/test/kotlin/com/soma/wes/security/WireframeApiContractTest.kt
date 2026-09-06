package com.soma.wes.security

import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.billing.domain.TestCheckout
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.ZonedDateTime

/** 실제 인증 필터와 JSON 직렬화를 지나 새 화면의 HTTP 계약을 검증한다. */
@IntegrationTest
class WireframeApiContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val tokens: AuthTokenProvider,
    private val users: UserFixture,
    private val checkouts: TestCheckoutRepository,
    private val galleries: GalleryRepository,
    private val galleryFixture: GalleryFixture,
    private val photos: PhotoFixture,
    private val selection: PhotoSelectionService,
) {
    @Test
    fun `기본 환경은 인증 뒤에도 실결제를 열지 않는다`() {
        mvc.get("/api/v1/plans").andExpect { status { isUnauthorized() } }
        val token = tokens.generateAccessToken(users.사용자()).value
        mvc.get("/api/v1/plans") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.mode") { value("DISABLED") }
            jsonPath("$.testCheckoutEnabled") { value(false) }
        }
        mvc.post("/api/v1/payments/checkout") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"planId":"test-30-days"}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("BILLING_503_1") }
        }
        assertThat(checkouts.count()).isZero()
    }

    @Test
    fun `개인 온보딩은 본인 테스트 이용권으로 한 번만 개설하고 설정을 저장한다`() {
        val owner = users.사용자()
        val token = tokens.generateAccessToken(owner).value
        val checkout = checkouts.save(TestCheckout(
            userId = owner.requiredId, planId = "test-30-days", amount = 0, currency = "KRW",
            maxPhotoCount = 100, expiresAt = ZonedDateTime.now().plusDays(30),
        ))
        val body = """{"checkoutId":"${checkout.id}","title":"우리의 사진","maxSelectablePhotoCount":20}"""
        mvc.post("/api/v1/galleries/personal") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.stage") { value("UPLOAD") }
            jsonPath("$.photoOrganizationRequired") { value(true) }
            jsonPath("$.planMaxPhotoCount") { value(100) }
        }
        val galleryId = galleries.findAll().single().requiredId
        mvc.patch("/api/v1/galleries/$galleryId/personal") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"본식 사진","maxSelectablePhotoCount":30}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("본식 사진") }
            jsonPath("$.maxSelectablePhotoCount") { value(30) }
        }
        mvc.post("/api/v1/galleries/personal") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("BILLING_409_1") }
        }
    }

    @Test
    fun `선택 제출의 지점 요청은 HTTP 입력과 JSONB 저장을 지나 작가에게 전달된다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리(maxSelectablePhotoCount = 1)
        val photoId = photos.업로드된_사진(fixture.galleryId, 1).single()
        selection.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = listOf(photoId)))
        val clientToken = tokens.generateAccessToken(fixture.member).value
        val managerToken = tokens.generateAccessToken(fixture.photographer).value
        mvc.post("/api/v1/galleries/${fixture.galleryId}/photo-selection/submit") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"requests":[{"photoId":$photoId,"requestText":"자연스럽게 보정해 주세요","points":[{"x":0.25,"y":0.75,"text":"이 부분을 정리해 주세요"}]}]}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUBMITTED") }
        }
        mvc.get("/api/v1/galleries/${fixture.galleryId}/retouch/rounds") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.currentRound.status") { value("REQUESTED") }
            jsonPath("$.currentRound.photos[0].points[0].x") { value(0.25) }
            jsonPath("$.currentRound.photos[0].points[0].text") { value("이 부분을 정리해 주세요") }
        }
    }
}
