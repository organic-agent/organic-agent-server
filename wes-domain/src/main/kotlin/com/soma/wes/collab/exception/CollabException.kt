package com.soma.wes.collab.exception

import com.soma.wes.global.exception.BusinessException

class CollabException(errorCode: CollabErrorCode) : BusinessException(errorCode)
