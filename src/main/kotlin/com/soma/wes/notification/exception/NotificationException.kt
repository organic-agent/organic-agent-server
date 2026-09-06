package com.soma.wes.notification.exception

import com.soma.wes.global.exception.BusinessException

class NotificationException(errorCode: NotificationErrorCode) : BusinessException(errorCode)
