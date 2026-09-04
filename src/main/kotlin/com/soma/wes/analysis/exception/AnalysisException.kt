package com.soma.wes.analysis.exception

import com.soma.wes.global.exception.BusinessException

class AnalysisException(errorCode: AnalysisErrorCode) : BusinessException(errorCode)
