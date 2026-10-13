package com.soma.wes.selection.exception

import com.soma.wes.global.exception.BusinessException

class SelectionException(errorCode: SelectionErrorCode) : BusinessException(errorCode)
