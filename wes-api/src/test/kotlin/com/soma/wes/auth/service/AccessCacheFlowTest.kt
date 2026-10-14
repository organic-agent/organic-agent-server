package com.soma.wes.auth.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.auth.config.AccessCacheProperties
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.auth.infrastructure.InMemoryAccessCache
import com.soma.wes.auth.repository.AuthAccessStatusRepository
import com.soma.wes.auth.token.JwtProvider
import com.soma.wes.auth.token.TokenStorage
import com.soma.wes.auth.token.config.JwtProperties
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.service.UserService
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 권한 확인 캐시가 켜졌을 때(wes-api, #286 (k))의 반영 지연. 통합 테스트 컨텍스트는 캐시가 꺼진 쪽이라, 같은 빈들로 캐시를 넣은
 * 인스턴스를 직접 만들고 시계를 돌려 TTL(30초)을 재현한다.
 */
@IntegrationTest
class AccessCacheFlowTest @Autowired constructor(
    private val authTokenProvider: AuthTokenProvider,
    private val jwtProvider: JwtProvider,
    private val jwtProperties: JwtProperties,
    private val tokenStorage: TokenStorage,
    private val userRepository: UserRepository,
    private val accessStatusRepository: AuthAccessStatusRepository,
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val notificationPublisher: UserNotificationPublisher,
    private val activityRepository: ActivityRepository,
    private val galleryFixture: GalleryFixture,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val cacheClock = MutableClock()
    private val cache = InMemoryAccessCache(AccessCacheProperties(enabled = true, ttl = TTL), cacheClock)

    private val cachedTokenProvider = AuthTokenProvider(
        jwtProvider = jwtProvider,
        jwtProperties = jwtProperties,
        tokenStorage = tokenStorage,
        userRepository = userRepository,
        accessStatusRepository = accessStatusRepository,
        accessCache = cache,
    )

    private val cachedPolicy = GalleryAccessPolicy(
        galleryRepository = galleryRepository,
        galleryMemberRepository = galleryMemberRepository,
        studioRepository = studioRepository,
        photoSelectionRepository = photoSelectionRepository,
        workspaceRepository = workspaceRepository,
        workspaceMemberRepository = workspaceMemberRepository,
        clock = clock,
        accessCache = cache,
    )

    private fun signUp(providerId: String) = userRepository.save(
        User(provider = OAuthProvider.KAKAO, providerId = providerId, nickname = "테스터"),
    )

    private fun suspendUser(userId: Long) {
        jdbcTemplate.update("UPDATE users SET suspended_at = now() WHERE id = ?", userId)
    }

    @Nested
    @DisplayName("계정을 확인할 때")
    inner class UserStatus {

        @Test
        fun `관리자 정지는 TTL 동안 담긴 값으로 통과하고 TTL이 지나면 막힌다`() {
            // given — 한 번 확인해 활성으로 담긴 계정
            val user = signUp("kakao-cache-1")
            val token = authTokenProvider.generateAccessToken(user)
            cachedTokenProvider.getAuthUser(token)

            // when — 관리자 앱이 정지(이 서버 메모리는 지울 수 없다)
            suspendUser(user.requiredId)

            // then
            assertThatCode { cachedTokenProvider.getAuthUser(token) }.doesNotThrowAnyException()
            cacheClock.advance(TTL)
            assertThatThrownBy { cachedTokenProvider.getAuthUser(token) }
                .isInstanceOfSatisfying(TokenException::class.java) { e -> assertThat(e.errorCode).isEqualTo(AuthErrorCode.USER_SUSPENDED) }
        }

        @Test
        fun `캐시가 없으면 지금처럼 정지가 바로 막힌다`() {
            // given
            val user = signUp("kakao-cache-2")
            val token = authTokenProvider.generateAccessToken(user)
            authTokenProvider.getAuthUser(token)

            // when
            suspendUser(user.requiredId)

            // then
            assertThatThrownBy { authTokenProvider.getAuthUser(token) }
                .isInstanceOfSatisfying(TokenException::class.java) { e -> assertThat(e.errorCode).isEqualTo(AuthErrorCode.USER_SUSPENDED) }
        }

        @Test
        fun `탈퇴는 커밋 뒤 캐시에서 지워 TTL을 기다리지 않고 막힌다`() {
            // given
            val user = signUp("kakao-cache-3")
            val token = authTokenProvider.generateAccessToken(user)
            cachedTokenProvider.getAuthUser(token)
            val userService = UserService(
                userRepository = userRepository,
                workspaceRepository = workspaceRepository,
                workspaceMemberRepository = workspaceMemberRepository,
                studioRepository = studioRepository,
                galleryRepository = galleryRepository,
                galleryMemberRepository = galleryMemberRepository,
                notificationPublisher = notificationPublisher,
                authTokenProvider = authTokenProvider,
                activityRepository = activityRepository,
                accessCache = cache,
            )

            // when
            transactionTemplate.executeWithoutResult { userService.delete(user.requiredId) }

            // then
            assertThatThrownBy { cachedTokenProvider.getAuthUser(token) }
                .isInstanceOfSatisfying(TokenException::class.java) { e -> assertThat(e.errorCode).isEqualTo(AuthErrorCode.TOKEN_OWNER_NOT_FOUND) }
        }
    }

    @Nested
    @DisplayName("갤러리 권한을 확인할 때")
    inner class GalleryAccess {

        @Test
        fun `스튜디오 정지는 TTL이 지나면 작가 권한을 막는다`() {
            // given — 한 번 확인해 스튜디오 활성으로 담겼다
            val gallery = galleryFixture.멤버와_열린_갤러리()
            val photographerId = gallery.photographer.requiredId
            cachedPolicy.requireManager(gallery.galleryId, photographerId)

            // when
            jdbcTemplate.update(
                "UPDATE studios SET suspended_at = now() WHERE workspace_id = (SELECT workspace_id FROM galleries WHERE id = ?)",
                gallery.galleryId,
            )

            // then
            assertThatCode { cachedPolicy.requireManager(gallery.galleryId, photographerId) }.doesNotThrowAnyException()
            cacheClock.advance(TTL)
            assertThatThrownBy { cachedPolicy.requireManager(gallery.galleryId, photographerId) }
                .isInstanceOfSatisfying(GalleryException::class.java) { e -> assertThat(e.errorCode).isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED) }
        }

        @Test
        fun `캐시를 거쳐도 역할 판단은 같다 — 초대 고객은 관리자가 아니고 보기는 된다`() {
            // given
            val gallery = galleryFixture.멤버와_열린_갤러리()
            val memberId = gallery.member.requiredId

            // when·then — 두 번째 호출은 담긴 작업공간으로 판단한다
            repeat(2) {
                assertThatThrownBy { cachedPolicy.requireManager(gallery.galleryId, memberId) }
                    .isInstanceOf(GalleryException::class.java)
                assertThatCode { cachedPolicy.requireViewer(gallery.galleryId, memberId) }.doesNotThrowAnyException()
            }
        }
    }

    /** 앞으로 돌릴 수 있는 시계 — 캐시의 TTL만 돌린다. */
    private class MutableClock : Clock() {
        private var instant: Instant = Instant.now()

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }

        override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    companion object {
        private val TTL: Duration = Duration.ofSeconds(30)
    }
}
