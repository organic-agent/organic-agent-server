package com.soma.wes.auth.service.oauth

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import com.soma.wes.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.util.UriComponentsBuilder
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 로그인 진입점이 트랜잭션 경계를 제대로 넘는지 확인한다.
 *
 * 이 클래스가 존재하는 이유 자체가 트랜잭션이다. 한때 `login()`이 [OAuthLoginProcessor] 안에
 * 있으면서 같은 클래스의 `process()`를 자기 호출했는데, Spring의 프록시를 거치지 않아
 * `@Transactional`이 조용히 무시됐다. 그 결과 기존 사용자의 프로필 갱신이 더티 체킹을 타지
 * 못하고 통째로 유실됐다 — 로그인은 성공하고 예외도 없어서 드러나지 않았다.
 *
 * 그래서 `process()`를 직접 부르는 테스트로는 이 회귀를 잡을 수 없다. 반드시 [login]을 통해야 한다.
 * provider 왕복만 대역으로 세운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class OAuthLoginServiceTest @Autowired constructor(
    private val oAuthLoginService: OAuthLoginService,
    private val oAuthLoginUrlService: OAuthLoginUrlService,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
) {

    @MockitoBean
    private lateinit var oAuthUserInfoService: OAuthUserInfoService

    private val sequence = AtomicLong(System.nanoTime())

    @Test
    fun `로그인하면 소셜에서 바뀐 프로필이 실제로 저장된다`() {
        val providerId = "oauth-login-${sequence.incrementAndGet()}"
        userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "옛 닉네임",
                email = "old@example.com",
            ),
        )

        whenever(oAuthUserInfoService.getUserInfo(eq(OAuthProvider.KAKAO), any(), anyOrNull())).thenReturn(
            OAuthUserInfo(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "새 닉네임",
                email = "new@example.com",
            ),
        )

        oAuthLoginService.login("kakao", AuthCodeRequest("auth-code"), "http://localhost:3000")

        // 트랜잭션이 열리지 않았다면 준영속 엔티티에 쓴 셈이라 옛 값이 그대로 남는다.
        val updated = userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)
        assertEquals("새 닉네임", updated?.nickname)
        assertEquals("new@example.com", updated?.email)
    }

    @Test
    fun `첫 로그인이면 가입시키고 토큰을 준다`() {
        val providerId = "oauth-login-new-${sequence.incrementAndGet()}"
        whenever(oAuthUserInfoService.getUserInfo(eq(OAuthProvider.KAKAO), any(), anyOrNull())).thenReturn(
            OAuthUserInfo(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "새 사용자",
                email = "new@example.com",
            ),
        )

        val response = oAuthLoginService.login("kakao", AuthCodeRequest("auth-code"), null)

        assertEquals("새 사용자", userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)?.nickname)
        assert(response.accessToken.isNotBlank())
        // 초대 없이 들어온 로그인이다. 갈 갤러리가 없다.
        assertNull(response.galleryId)
    }

    @Test
    fun `초대 링크로 로그인하면 가입과 수락이 한 번에 끝난다`() {
        // 링크 클릭 → 카카오 로그인 → 갤러리 도착. 중간에 별도 수락 호출이 없어야
        // 그 사이에서 흐름이 끊길 구간도 없다.
        val gallery = saveGalleryWithInvite("invite-happy")
        val providerId = stubNewUser("oauth-invite")

        // ① 프론트가 /invite/{token}에서 로그인 URL을 받는다
        val state = stateOf(oAuthLoginUrlService.generateLoginUrl("kakao", null, "invite-happy").loginUrl)

        // ② 콜백이 돌려준 state를 그대로 넘긴다
        val response = oAuthLoginService.login("kakao", AuthCodeRequest("auth-code", state), null)

        assertEquals(gallery.id, response.galleryId)
        val user = assertNotNull(userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId))
        assertEquals(UserType.CLIENT, user.userType)
        assertNotNull(galleryMemberRepository.findByGalleryIdAndUserId(gallery.id!!, user.id!!))
    }

    @Test
    fun `초대 토큰은 state로 나가지 않는다`() {
        saveGalleryWithInvite("invite-secret")

        val loginUrl = oAuthLoginUrlService.generateLoginUrl("kakao", null, "invite-secret").loginUrl

        assert(!loginUrl.contains("invite-secret")) { "초대 토큰이 provider로 나가면 안 된다: $loginUrl" }
    }

    @Test
    fun `state가 없으면 로그인만 되고 갤러리는 비어서 온다`() {
        // 인앱 브라우저 전환 등으로 state를 잃은 경우다. 사용자는 카톡에 남은 링크를 다시
        // 눌러 수락 API로 합류한다.
        saveGalleryWithInvite("invite-lost")
        val providerId = stubNewUser("oauth-nostate")

        val response = oAuthLoginService.login("kakao", AuthCodeRequest("auth-code"), null)

        assertNull(response.galleryId)
        assert(response.accessToken.isNotBlank())
        // 수락을 안 했으므로 종류도 아직 정해지지 않는다.
        assertNull(userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId)?.userType)
    }

    @Test
    fun `만료된 초대여도 로그인은 성공한다`() {
        // 링크 하나가 방금 만든 계정까지 되돌리면 안 된다. 수락만 조용히 실패시킨다.
        saveGalleryWithInvite("invite-expired", expiresAt = ZonedDateTime.now().minusDays(1))
        val providerId = stubNewUser("oauth-expired")

        val state = stateOf(oAuthLoginUrlService.generateLoginUrl("kakao", null, "invite-expired").loginUrl)
        val response = oAuthLoginService.login("kakao", AuthCodeRequest("auth-code", state), null)

        assertNull(response.galleryId)
        assert(response.accessToken.isNotBlank())
        assertNotNull(userRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, providerId))
    }

    @Test
    fun `같은 state로 두 번 로그인해도 두 번째는 초대가 붙지 않는다`() {
        val gallery = saveGalleryWithInvite("invite-replay")
        stubNewUser("oauth-replay")

        val state = stateOf(oAuthLoginUrlService.generateLoginUrl("kakao", null, "invite-replay").loginUrl)

        assertEquals(gallery.id, oAuthLoginService.login("kakao", AuthCodeRequest("c", state), null).galleryId)
        assertNull(oAuthLoginService.login("kakao", AuthCodeRequest("c", state), null).galleryId)
    }

    // --- helpers ---

    /** provider 왕복을 대역으로 세우고, 그 사용자의 providerId를 돌려준다. */
    private fun stubNewUser(prefix: String): String {
        val providerId = "$prefix-${sequence.incrementAndGet()}"
        whenever(oAuthUserInfoService.getUserInfo(eq(OAuthProvider.KAKAO), any(), anyOrNull())).thenReturn(
            OAuthUserInfo(
                provider = OAuthProvider.KAKAO,
                providerId = providerId,
                nickname = "예비 부부",
                email = "$providerId@example.com",
            ),
        )
        return providerId
    }

    private fun saveGalleryWithInvite(
        token: String,
        expiresAt: ZonedDateTime = ZonedDateTime.now().plusDays(7),
    ): Gallery {
        val suffix = sequence.incrementAndGet()
        val photographer = userRepository.save(
            User(
                provider = OAuthProvider.KAKAO,
                providerId = "photographer-$suffix",
                nickname = "작가",
                email = "photographer-$suffix@example.com",
            ),
        )
        val studio = studioRepository.save(
            Studio(userId = photographer.id!!, name = "스튜디오", galleryUrl = "studio-$suffix"),
        )
        val gallery = galleryRepository.save(
            Gallery(studioId = studio.id!!, title = "본식", status = GalleryStatus.OPEN),
        )
        galleryInviteRepository.save(
            GalleryInvite(galleryId = gallery.id!!, token = token, expiresAt = expiresAt),
        )
        return gallery
    }

    /** 로그인 URL에 실린 state를 꺼낸다. 프론트는 콜백 쿼리스트링에서 같은 값을 받는다. */
    private fun stateOf(loginUrl: String): String =
        UriComponentsBuilder.fromUriString(loginUrl).build().queryParams.getFirst("state")!!
}
