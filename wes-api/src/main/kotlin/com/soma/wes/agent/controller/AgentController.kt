package com.soma.wes.agent.controller

import com.soma.wes.agent.controller.docs.AgentControllerDocs
import com.soma.wes.agent.dto.request.AgentActionRequest
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.dto.response.AgentActionResponse
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.service.AgentActionService
import com.soma.wes.agent.service.AgentMessageService
import com.soma.wes.auth.domain.LoginUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/agent")
class AgentController(
    private val agentMessageService: AgentMessageService,
    private val agentActionService: AgentActionService,
) : AgentControllerDocs {

    @PostMapping("/messages")
    override fun send(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: AgentMessageRequest,
    ): ResponseEntity<AgentMessageResponse> {
        val result = agentMessageService.send(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/actions")
    override fun execute(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: AgentActionRequest,
    ): ResponseEntity<AgentActionResponse> {
        val result = agentActionService.execute(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }
}
