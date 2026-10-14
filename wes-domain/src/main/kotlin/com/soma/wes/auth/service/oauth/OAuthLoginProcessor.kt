package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.dto.OAuthLoginResultDto
import com.soma.wes.auth.dto.OAuthUserInfoDto
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.workspace.service.WorkspaceService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class OAuthLoginProcessor(
    private val userRepository: UserRepository,
    private val authTokenProvider: AuthTokenProvider,
    private val workspaceService: WorkspaceService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun process(userInfo: OAuthUserInfoDto): OAuthLoginResultDto {
        val user = findOrCreateUser(userInfo)
        workspaceService.ensurePersonalWorkspace(user)

        val accessToken = authTokenProvider.generateAccessToken(user)
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        return OAuthLoginResultDto(
            userId = user.requiredId,
            response = LoginResponse.of(accessToken, refreshToken),
        )
    }

    private fun findOrCreateUser(userInfo: OAuthUserInfoDto): User {
        val user = userRepository.findByProviderAndProviderId(userInfo.provider, userInfo.providerId)

        if (user != null) {
            // 서비스에서 바꾼 닉네임은 유지하고 소셜 이메일·프로필 사진을 동기화한다.
            user.syncProviderProfile(email = userInfo.email, profileImageUrl = userInfo.profileImageUrl)
            return user
        }

        log.info("첫 로그인, 회원가입을 진행합니다 (provider={})", userInfo.provider)
        return userRepository.save(
            User(
                provider = userInfo.provider,
                providerId = userInfo.providerId,
                nickname = userInfo.nickname,
                email = userInfo.email,
                profileImageUrl = userInfo.profileImageUrl,
            ),
        )
    }
}
