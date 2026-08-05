package com.soma.wes.user.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.user.controller.docs.UserControllerDocs
import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) : UserControllerDocs {


    @GetMapping("/me")
    override fun getMe(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<UserResponse> {
        val result = userService.getUser(loginUser.id)

        return ResponseEntity.ok(result)
    }
}
