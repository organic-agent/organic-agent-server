package com.soma.wes.admin.controller

import com.soma.wes.admin.controller.docs.AdminCouponControllerDocs
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeProCouponStatusRequest
import com.soma.wes.admin.dto.response.AdminProCouponResponse
import com.soma.wes.admin.dto.response.IssueProCouponResponse
import com.soma.wes.admin.service.AdminCouponService
import com.soma.wes.global.page.PageResponse
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/admin/v1/pro-coupons")
class AdminCouponController(private val service: AdminCouponService) : AdminCouponControllerDocs {
    @PostMapping
    override fun issue(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @Valid @RequestBody request: AdminReasonRequest,
    ): ResponseEntity<IssueProCouponResponse> {
        val status = HttpStatus.CREATED
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(service.issue(loginUser.id, request))
    }

    @GetMapping
    override fun getCoupons(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam(required = false) status: AdminProCouponStatus?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): ResponseEntity<PageResponse<AdminProCouponResponse>> {
        val result = service.getCoupons(actorAdminId = loginUser.id, status = status, query = query, page = page, size = size)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    @GetMapping("/{couponId}")
    override fun getCoupon(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable couponId: Long,
    ): ResponseEntity<AdminProCouponResponse> {
        val result = service.getCoupon(couponId = couponId, actorAdminId = loginUser.id)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }

    @PatchMapping("/{couponId}/status")
    override fun changeStatus(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable couponId: Long,
        @Valid @RequestBody request: ChangeProCouponStatusRequest,
    ): ResponseEntity<AdminProCouponResponse> {
        val result = service.changeStatus(couponId = couponId, actorAdminId = loginUser.id, request = request)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result)
    }
}
