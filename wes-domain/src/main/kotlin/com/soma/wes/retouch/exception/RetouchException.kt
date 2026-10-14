package com.soma.wes.retouch.exception

import com.soma.wes.global.exception.BusinessException

class RetouchException(errorCode: RetouchErrorCode) : BusinessException(errorCode)
