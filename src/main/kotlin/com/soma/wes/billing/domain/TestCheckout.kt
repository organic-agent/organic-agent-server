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
    @Column(length = 36)
    val id: String = UUID.randomUUID().toString(),
    @Column(nullable = false, updatable = false)
    val userId: Long,
    @Column(nullable = false, length = 100, updatable = false)
    val planId: String,
    @Column(nullable = false, updatable = false)
    val amount: Long,
    @Column(nullable = false, length = 3, updatable = false)
    val currency: String,
    @Column(nullable = false, updatable = false)
    val maxPhotoCount: Int,
    @Column(nullable = false, updatable = false)
    val expiresAt: ZonedDateTime,
    var galleryId: Long? = null,
    var consumedAt: ZonedDateTime? = null,
) : BaseEntity()
