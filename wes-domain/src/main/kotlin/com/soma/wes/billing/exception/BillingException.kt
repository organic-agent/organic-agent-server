package com.soma.wes.billing.exception

import com.soma.wes.global.exception.BusinessException

class BillingException(errorCode: BillingErrorCode) : BusinessException(errorCode)
