package com.soma.wes.user.exception

import com.soma.wes.global.exception.BusinessException

class UserException(errorCode: UserErrorCode) : BusinessException(errorCode)
