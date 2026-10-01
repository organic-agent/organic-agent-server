package com.soma.wes.billing.support

import com.soma.wes.billing.domain.ProCoupon
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Locale

@Component
class CouponCodes {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(CODE_BYTES)
        random.nextBytes(bytes)
        return "$CODE_PREFIX${HexFormat.of().withUpperCase().formatHex(bytes)}"
    }

    fun hash(code: String): String {
        if (code.length > ProCoupon.MAX_CODE_LENGTH) throw BillingException(BillingErrorCode.INVALID_COUPON_CODE)
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (!CODE_FORMAT.matches(normalized)) throw BillingException(BillingErrorCode.INVALID_COUPON_CODE)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        /** 일회용 쿠폰 코드에 128비트 난수로 추측 불가능한 값을 넣는다. */
        private const val CODE_BYTES = 16
        private const val CODE_PREFIX = "WES-"
        private val CODE_FORMAT = Regex("WES-[0-9A-F]{${CODE_BYTES * 2}}")
    }
}
