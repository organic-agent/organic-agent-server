package com.soma.wes.billing.domain

import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

/** 코드 원문은 저장하지 않는다. 계정·갤러리가 삭제되어도 등록·사용 시각을 남겨 재사용을 막는다. */
@Entity
@Table(name = "pro_coupons")
class ProCoupon private constructor(
    @Column(name = "code_hash", nullable = false, updatable = false, unique = true, length = CODE_HASH_LENGTH)
    val codeHash: String,
    @Column(name = "code_suffix", nullable = true, updatable = false, length = CODE_SUFFIX_LENGTH)
    val codeSuffix: String? = null,
    @Column(name = "issued_by_admin_id", nullable = true, updatable = false)
    val issuedByAdminId: Long? = null,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "아직 저장되지 않은 프로 쿠폰이다." }

    @Column(name = "user_id", nullable = true)
    var userId: Long? = null
        protected set

    @Column(name = "registered_at", nullable = true)
    var registeredAt: ZonedDateTime? = null
        protected set

    @Column(name = "consumed_at", nullable = true)
    var consumedAt: ZonedDateTime? = null
        protected set

    @Column(name = "gallery_id", nullable = true)
    var galleryId: Long? = null
        protected set

    @Column(name = "expires_at", nullable = true)
    var expiresAt: ZonedDateTime? = null
        protected set

    @Column(name = "disabled_at", nullable = true)
    var disabledAt: ZonedDateTime? = null
        protected set

    fun register(userId: Long, at: ZonedDateTime) {
        requireEnabled()
        if (registeredAt != null) {
            if (this.userId == userId) return
            throw BillingException(BillingErrorCode.COUPON_CODE_ALREADY_REGISTERED)
        }
        this.userId = userId
        registeredAt = at
    }

    fun requireUnused() {
        requireEnabled()
        if (consumedAt != null) throw BillingException(BillingErrorCode.COUPON_ALREADY_USED)
    }

    fun consume(galleryId: Long, at: ZonedDateTime, expiresAt: ZonedDateTime) {
        requireUnused()
        checkNotNull(registeredAt) { "등록되지 않은 프로 쿠폰은 사용할 수 없다." }
        this.galleryId = galleryId
        consumedAt = at
        this.expiresAt = expiresAt
    }

    private fun requireEnabled() {
        if (disabledAt != null) throw BillingException(BillingErrorCode.COUPON_DISABLED)
    }

    /** 이미 갤러리에 사용한 쿠폰의 변경은 갤러리 이용 기간에 영향을 줄 수 있으므로 금지한다. */
    fun changeEnabled(enabled: Boolean, at: ZonedDateTime) {
        if (consumedAt != null) throw BillingException(BillingErrorCode.COUPON_ALREADY_USED)
        disabledAt = if (enabled) null else at
    }

    companion object {
        /** 관리자 목록은 코드 전체 대신 끝 8자리만 표시한다. */
        const val CODE_SUFFIX_LENGTH = 8
        /** SHA-256을 16진수로 인코딩한 길이. */
        const val CODE_HASH_LENGTH = 64
        /** 사람이 입력하는 쿠폰 코드의 API 상한. */
        const val MAX_CODE_LENGTH = 80

        fun of(codeHash: String, codeSuffix: String? = null, issuedByAdminId: Long? = null): ProCoupon {
            if (!Regex("[0-9a-f]{$CODE_HASH_LENGTH}").matches(codeHash)) {
                throw BillingException(BillingErrorCode.INVALID_COUPON_CODE)
            }
            if (codeSuffix != null && !Regex("[0-9A-F]{$CODE_SUFFIX_LENGTH}").matches(codeSuffix)) {
                throw BillingException(BillingErrorCode.INVALID_COUPON_CODE)
            }
            return ProCoupon(codeHash = codeHash, codeSuffix = codeSuffix, issuedByAdminId = issuedByAdminId)
        }
    }
}
