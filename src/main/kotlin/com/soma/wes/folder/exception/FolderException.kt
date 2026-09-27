package com.soma.wes.category.exception

import com.soma.wes.global.exception.BusinessException

class CategoryException(errorCode: CategoryErrorCode) : BusinessException(errorCode)
