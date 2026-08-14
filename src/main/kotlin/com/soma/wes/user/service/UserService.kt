package com.soma.wes.user.service

import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getUser(id: Long): UserResponse =
        UserResponse.from(userRepository.requireById(id))
}
