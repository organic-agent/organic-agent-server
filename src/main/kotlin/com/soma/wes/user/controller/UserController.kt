package com.soma.wes.user.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.user.controller.docs.UserControllerDocs
import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.service.UserService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) : UserControllerDocs {

    /**
     * access token의 주체에 해당하는 사용자 정보를 조회한다.
     */
    @GetMapping("/me")
    override fun getMe(@AuthenticationPrincipal loginUser: LoginUser): UserResponse =
        userService.getUser(loginUser.id)
}
