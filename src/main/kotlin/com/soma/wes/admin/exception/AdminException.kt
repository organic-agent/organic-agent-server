package com.soma.wes.admin.exception

import com.soma.wes.global.exception.BusinessException

class AdminException(
    errorCode: AdminErrorCode,
) : BusinessException(errorCode)
