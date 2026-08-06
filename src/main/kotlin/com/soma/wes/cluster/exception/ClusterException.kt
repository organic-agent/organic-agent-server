package com.soma.wes.cluster.exception

import com.soma.wes.global.exception.BusinessException

class ClusterException(errorCode: ClusterErrorCode) : BusinessException(errorCode)
