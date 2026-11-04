package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime
import java.util.UUID

/** 결제수단이나 PG 자격증명은 저장하지 않는 테스트 이용권이다. 플랜 값은 구매 시점에 고정한다. */
@Entity
@Table(name = "test_checkouts")
class TestCheckout(
    @Id
    @Column(name = "id", length = 36)
    val id: String = UUID.randomUUID().toString(),
    @Column(name = "user_id", updatable = false)
    val userId: Long,
    @Column(name = "plan_id", length = 100, updatable = false)
    val planId: String,
    @Column(name = "amount", updatable = false)
    val amount: Long,
    @Column(name = "currency", length = 3, updatable = false)
    val currency: String,
    @Column(name = "max_photo_count", updatable = false)
    val maxPhotoCount: Int,
    @Column(name = "expires_at", updatable = false)
    val expiresAt: ZonedDateTime,
    galleryId: Long? = null,
    consumedAt: ZonedDateTime? = null,
) : BaseEntity() {

    @Column(name = "gallery_id")
    var galleryId: Long? = galleryId
        protected set

    @Column(name = "consumed_at")
    var consumedAt: ZonedDateTime? = consumedAt
        protected set

    fun consume(galleryId: Long, at: ZonedDateTime) {
        this.galleryId = galleryId
        consumedAt = at
    }
}
