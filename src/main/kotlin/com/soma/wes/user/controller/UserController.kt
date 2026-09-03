package com.soma.wes.user.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.user.controller.docs.UserControllerDocs
import com.soma.wes.user.dto.request.UpdateUserRequest
import com.soma.wes.user.dto.response.UserResponse
import com.soma.wes.user.dto.response.UserWorkspaceResponse
import com.soma.wes.user.service.UserService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
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

    @PatchMapping("/me")
    override fun updateMe(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: UpdateUserRequest,
    ): ResponseEntity<UserResponse> = ResponseEntity.ok(userService.update(loginUser.id, request))

    @GetMapping("/me/workspaces")
    override fun listMyWorkspaces(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<List<UserWorkspaceResponse>> =
        ResponseEntity.ok(userService.listWorkspaces(loginUser.id))

    @DeleteMapping("/me")
    override fun deleteMe(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<Unit> {
        userService.delete(loginUser.id)
        return ResponseEntity.noContent().build()
    }
}
