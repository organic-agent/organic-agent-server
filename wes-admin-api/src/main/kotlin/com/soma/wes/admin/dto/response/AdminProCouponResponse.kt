package com.soma.wes.admin.dto.response

import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.AdminProCouponDto
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AdminProCouponResponse(
    @field:Schema(description = "발급 코드 관리 id", example = "1") val couponId: Long,
    @field:Schema(description = "상태 변경의 expectedVersion. 사용자 등록·사용에도 증가한다.") val version: Long,
    @field:Schema(description = "코드 끝 8자리. 이전에 발급한 코드는 null이며 원문은 반환하지 않는다.") val codeSuffix: String?,
    @field:Schema(description = "발급·등록·사용·기간 만료·비활성 상태") val status: AdminProCouponStatus,
    @field:Schema(description = "발급 관리자 id. 이전 코드 또는 삭제된 관리자이면 null") val issuedByAdminId: Long?,
    @field:Schema(description = "발급 시각") val createdAt: ZonedDateTime,
    @field:Schema(description = "등록 계정 id. 미등록 또는 영구 삭제된 계정이면 null") val userId: Long?,
    @field:Schema(description = "현재 등록 계정 이름. 삭제된 계정이면 null") val userNickname: String?,
    @field:Schema(description = "코드 등록 시각. 이용 기간은 시작하지 않는다.") val registeredAt: ZonedDateTime?,
    @field:Schema(description = "새 갤러리를 생성하면서 소비한 시각") val consumedAt: ZonedDateTime?,
    @field:Schema(description = "연결 갤러리 id. 영구 삭제되면 null") val galleryId: Long?,
    @field:Schema(description = "현재 연결 갤러리 이름. 휴지통 또는 삭제된 갤러리는 null") val galleryTitle: String?,
    @field:Schema(description = "사용일부터 달력 기준 1년의 만료 시각. 미사용이면 null") val expiresAt: ZonedDateTime?,
    @field:Schema(description = "미사용 코드 비활성화 시각. 활성 코드이면 null") val disabledAt: ZonedDateTime?,
) {
    companion object {
        fun from(row: AdminProCouponDto): AdminProCouponResponse = AdminProCouponResponse(
            couponId = row.couponId, version = row.version, codeSuffix = row.codeSuffix, status = row.status,
            issuedByAdminId = row.issuedByAdminId, createdAt = row.createdAt, userId = row.userId,
            userNickname = row.userNickname, registeredAt = row.registeredAt, consumedAt = row.consumedAt,
            galleryId = row.galleryId, galleryTitle = row.galleryTitle, expiresAt = row.expiresAt,
            disabledAt = row.disabledAt,
        )
    }
}
