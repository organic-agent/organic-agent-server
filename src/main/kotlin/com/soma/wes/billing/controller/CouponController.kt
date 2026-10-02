package com.soma.wes.billing.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.billing.controller.docs.CouponControllerDocs
import com.soma.wes.billing.dto.request.RegisterProCouponRequest
import com.soma.wes.billing.dto.response.MyBenefitsResponse
import com.soma.wes.billing.dto.response.ProCouponResponse
import com.soma.wes.billing.service.CouponService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class CouponController(private val service: CouponService) : CouponControllerDocs {
    @GetMapping("/billing/me")
    override fun getMyBenefits(@AuthenticationPrincipal loginUser: LoginUser): ResponseEntity<MyBenefitsResponse> {
        return ResponseEntity.ok(service.getMyBenefits(loginUser.id))
    }

    @PostMapping("/coupons/register")
    override fun register(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: RegisterProCouponRequest,
    ): ResponseEntity<ProCouponResponse> {
        val status = HttpStatus.CREATED
        return ResponseEntity.status(status).body(service.register(loginUser.id, request))
    }
}
