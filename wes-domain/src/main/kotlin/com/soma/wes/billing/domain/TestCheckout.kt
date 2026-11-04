package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime
import java.util.UUID

/** 결제수단이나 PG 자격증명은 저장하지 않는 테스트 이용권이다. 플랜 값은 구매 시점에 고정한다. */
@Entity
@Table(name = "test_checkouts")
// 이 테이블은 감사 시각이 NOT NULL이다. BaseEntity의 공용 정의(NULL 허용)를 이 엔티티에서만 덮는다.
@AttributeOverride(name = "createdAt", column = Column(name = "created_at", nullable = false, updatable = false))
@AttributeOverride(name = "updatedAt", column = Column(name = "updated_at", nullable = false))
class TestCheckout(
    @Id
    @Column(name = "id", nullable = false, length = 36)
    val id: String = UUID.randomUUID().toString(),
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
    @Column(name = "plan_id", nullable = false, length = 100, updatable = false)
    val planId: String,
    @Column(name = "amount", nullable = false, updatable = false)
    val amount: Long,
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    val currency: String,
    @Column(name = "max_photo_count", nullable = false, updatable = false)
    val maxPhotoCount: Int,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: ZonedDateTime,
    galleryId: Long? = null,
    consumedAt: ZonedDateTime? = null,
) : BaseEntity() {

    @Column(name = "gallery_id", nullable = true)
    var galleryId: Long? = galleryId
        protected set

    @Column(name = "consumed_at", nullable = true)
    var consumedAt: ZonedDateTime? = consumedAt
        protected set

    fun consume(galleryId: Long, at: ZonedDateTime) {
        this.galleryId = galleryId
        consumedAt = at
    }
}
