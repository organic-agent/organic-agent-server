package com.soma.wes.recommendation.exception

import com.soma.wes.global.exception.BusinessException

class RecommendationException(errorCode: RecommendationErrorCode) : BusinessException(errorCode)
