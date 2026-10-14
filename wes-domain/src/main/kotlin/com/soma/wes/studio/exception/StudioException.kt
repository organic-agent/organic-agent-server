package com.soma.wes.studio.exception

import com.soma.wes.global.exception.BusinessException

class StudioException(errorCode: StudioErrorCode) : BusinessException(errorCode)
