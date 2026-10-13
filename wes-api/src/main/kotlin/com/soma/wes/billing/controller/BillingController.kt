package com.soma.wes.billing.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.billing.controller.docs.BillingControllerDocs
import com.soma.wes.billing.dto.request.CheckoutRequest
import com.soma.wes.billing.dto.response.CheckoutResponse
import com.soma.wes.billing.dto.response.PlansResponse
import com.soma.wes.billing.service.BillingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class BillingController(private val service: BillingService) : BillingControllerDocs {
    @GetMapping("/plans")
    override fun plans(): ResponseEntity<PlansResponse> {
        return ResponseEntity.ok(service.getPlans())
    }
    @PostMapping("/payments/checkout")
    override fun checkout(@AuthenticationPrincipal loginUser: LoginUser, @Valid @RequestBody request: CheckoutRequest): ResponseEntity<CheckoutResponse> {
        val status = HttpStatus.CREATED
        return ResponseEntity.status(status).body(service.checkout(loginUser.id, request))
    }
    @GetMapping("/payments/checkout/{checkoutId}")
    override fun getCheckout(@AuthenticationPrincipal loginUser: LoginUser, @PathVariable checkoutId: String): ResponseEntity<CheckoutResponse> {
        return ResponseEntity.ok(service.getCheckout(checkoutId, loginUser.id))
    }
}
