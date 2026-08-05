package com.soma.wes.auth.service.oauth

import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class OAuthLoginProcessor(
    private val userRepository: UserRepository,
    private val authTokenProvider: AuthTokenProvider,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun process(userInfo: OAuthUserInfo): LoginResponse {
        val user = findOrCreateUser(userInfo)

        val accessToken = authTokenProvider.generateAccessToken(user)
        val refreshToken = authTokenProvider.generateRefreshToken(user)

        return LoginResponse.of(accessToken, refreshToken)
    }

    private fun findOrCreateUser(userInfo: OAuthUserInfo): User {
        val user = userRepository.findByProviderAndProviderId(userInfo.provider, userInfo.providerId)

        if (user != null) {
            // provider 쪽에서 닉네임이나 이메일을 바꿨을 수 있으므로 로그인할 때마다 맞춘다.
            user.updateProfile(nickname = userInfo.nickname, email = userInfo.email)
            return user
        }

        log.info("첫 로그인, 회원가입을 진행합니다 (provider={})", userInfo.provider)
        return userRepository.save(
            User(
                provider = userInfo.provider,
                providerId = userInfo.providerId,
                nickname = userInfo.nickname,
                email = userInfo.email,
            ),
        )
    }
}
