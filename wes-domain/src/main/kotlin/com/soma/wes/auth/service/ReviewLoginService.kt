package com.soma.wes.auth.service

import com.soma.wes.auth.config.ReviewLoginProperties
import com.soma.wes.auth.dto.request.ReviewLoginRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.service.WorkspaceService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate

/**
 * 심사위원이 소셜 로그인 없이 링크 하나로 들어오게 한다.
 *
 * 소셜 로그인은 "누구인지 확인"에만 쓰이고 그 뒤는 우리가 발급한 토큰으로 돈다. 여기서는 확인을
 * 비밀 코드로 바꾸고, 정해 둔 개인 사용자 계정으로 같은 토큰을 발급한다([OAuthLoginProcessor]와 같은 출구).
 *
 * 꺼짐·만료·틀린 코드·계정 없음은 모두 같은 404로 답한다 — 어느 쪽인지 알려 주면 코드 추측을 돕는다.
 */
@Service
class ReviewLoginService(
    private val properties: ReviewLoginProperties,
    private val userRepository: UserRepository,
    private val authTokenProvider: AuthTokenProvider,
    private val workspaceService: WorkspaceService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun login(request: ReviewLoginRequest): LoginResponse {
        if (!properties.isOpenOn(LocalDate.now(clock)) || !matches(request.code)) {
            throw OAuthException(AuthErrorCode.REVIEW_LINK_NOT_FOUND)
        }

        val user = userRepository.findByIdOrNull(properties.userId)
        if (user == null) {
            log.warn("심사용 계정이 없습니다. app.review-login.user-id를 확인하세요 (userId={})", properties.userId)
            throw OAuthException(AuthErrorCode.REVIEW_LINK_NOT_FOUND)
        }

        workspaceService.ensurePersonalWorkspace(user)
        log.info("심사용 링크로 로그인했습니다 (userId={})", user.requiredId)

        return LoginResponse.of(
            authTokenProvider.generateAccessToken(user),
            authTokenProvider.generateRefreshToken(user),
        )
    }

    /** 응답 시간으로 코드를 한 글자씩 맞혀 가지 못하게 상수 시간으로 비교한다. */
    private fun matches(code: String): Boolean =
        MessageDigest.isEqual(code.toByteArray(), properties.code.toByteArray())
}
