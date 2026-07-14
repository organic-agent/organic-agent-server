package com.soma.wes.user.service

import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import com.soma.wes.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getUser(id: Long): UserResponse =
        userRepository.findById(id)
            .map(UserResponse::from)
            .orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }
}
