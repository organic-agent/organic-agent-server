package com.soma.wes.trash.exception

import com.soma.wes.global.exception.BusinessException

class TrashException(errorCode: TrashErrorCode) : BusinessException(errorCode)
