package com.soma.wes.notification.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_notification_settings")
class UserNotificationSetting(
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,

    @Column(name = "email_enabled", nullable = false)
    var emailEnabled: Boolean = true,

    @Column(name = "browser_enabled", nullable = false)
    var browserEnabled: Boolean = true,
) : BaseEntity() {
    fun update(emailEnabled: Boolean, browserEnabled: Boolean) {
        this.emailEnabled = emailEnabled
        this.browserEnabled = browserEnabled
    }
}
