package com.soma.wes.global

import jakarta.persistence.Column
import jakarta.persistence.EntityListeners
import jakarta.persistence.MappedSuperclass
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.ZonedDateTime

/**
 * 생성·수정 시각을 자동으로 남긴다.
 *
 * 시각은 [com.soma.wes.global.config.TimeConfig]의 `Clock` 빈에서 온다.
 * 엔티티가 직접 `now()`를 부르지 않으므로 테스트에서 시간을 고정할 수 있다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener::class)
abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    var createdAt: ZonedDateTime? = null
        protected set

    @LastModifiedDate
    @Column(name = "updated_at")
    var updatedAt: ZonedDateTime? = null
        protected set
}
