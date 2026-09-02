package com.soma.wes.notification.service

import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType

/** 제품 흐름이 알림 저장 방식이나 관리자 런타임에 결합되지 않게 하는 출력 경계다. */
fun interface UserNotificationPublisher {
    fun publish(
        userIds: Collection<Long>,
        type: UserNotificationType,
        scope: UserNotificationScope,
        scopeId: Long?,
        title: String,
        message: String,
    )
}
