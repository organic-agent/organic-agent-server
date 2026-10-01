package com.soma.wes.admin.repository

import com.soma.wes.admin.domain.AdminProCouponStatus
import com.soma.wes.admin.dto.AdminProCouponDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Repository
class AdminProCouponRepository(private val jdbc: JdbcClient) {
    /** 등록 계정·갤러리 이름은 현재 살아 있는 행에서만 조립하고 코드 원문·해시는 조회하지 않는다. */
    fun search(status: AdminProCouponStatus?, query: String, at: ZonedDateTime, pageable: Pageable): Page<AdminProCouponDto> {
        val literalQuery = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val params = mapOf(
            "at" to at.toOffsetDateTime(), "status" to status?.name.orEmpty(), "query" to "%$literalQuery%",
            "limit" to pageable.pageSize, "offset" to pageable.offset,
        )
        val count = jdbc.sql("$COUPON_STATES SELECT count(*) FROM coupon_states c $FILTER")
            .params(params).query(Long::class.java).single()
        val contents = jdbc.sql("$COUPON_STATES SELECT * FROM coupon_states c $FILTER ORDER BY c.created_at DESC, c.id DESC LIMIT :limit OFFSET :offset")
            .params(params).query { rs, _ -> mapRow(rs) }.list()
        return PageImpl(contents, pageable, count)
    }

    fun find(id: Long, at: ZonedDateTime): AdminProCouponDto? = jdbc
        .sql("$COUPON_STATES SELECT * FROM coupon_states WHERE id = :id")
        .param("at", at.toOffsetDateTime()).param("id", id)
        .query { rs, _ -> mapRow(rs) }.optional().orElse(null)

    private fun mapRow(rs: ResultSet): AdminProCouponDto = AdminProCouponDto(
        couponId = rs.getLong("id"), version = rs.getLong("version"), codeSuffix = rs.getString("code_suffix"),
        status = AdminProCouponStatus.valueOf(rs.getString("status")),
        issuedByAdminId = rs.getObject("issued_by_admin_id", java.lang.Long::class.java)?.toLong(),
        createdAt = checkNotNull(rs.time("created_at")) { "발급한 쿠폰에 생성 시각이 없다." },
        userId = rs.getObject("user_id", java.lang.Long::class.java)?.toLong(),
        userNickname = rs.getString("user_nickname"), registeredAt = rs.time("registered_at"),
        consumedAt = rs.time("consumed_at"), galleryId = rs.getObject("gallery_id", java.lang.Long::class.java)?.toLong(),
        galleryTitle = rs.getString("gallery_title"), expiresAt = rs.time("expires_at"), disabledAt = rs.time("disabled_at"),
    )

    private fun ResultSet.time(column: String): ZonedDateTime? = getObject(column, OffsetDateTime::class.java)?.toZonedDateTime()

    companion object {
        private val COUPON_STATES = """
            WITH coupon_states AS (
                SELECT c.id, c.version, c.code_suffix, c.issued_by_admin_id, c.created_at,
                       c.user_id, u.nickname AS user_nickname, c.registered_at, c.consumed_at,
                       c.gallery_id, g.title AS gallery_title, c.expires_at, c.disabled_at,
                       CASE WHEN c.disabled_at IS NOT NULL THEN 'DISABLED'
                            WHEN c.registered_at IS NULL THEN 'ISSUED'
                            WHEN c.consumed_at IS NULL THEN 'REGISTERED'
                            WHEN c.expires_at <= :at THEN 'EXPIRED'
                            ELSE 'USED' END AS status
                FROM pro_coupons c
                LEFT JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL
                LEFT JOIN galleries g ON g.id = c.gallery_id AND g.deleted_at IS NULL
            )
        """.trimIndent()
        private val FILTER = """
            WHERE (:status = '' OR c.status = :status)
              AND (c.id::text LIKE :query OR c.user_id::text LIKE :query
                   OR c.code_suffix ILIKE :query OR c.user_nickname ILIKE :query)
        """.trimIndent()
    }
}
