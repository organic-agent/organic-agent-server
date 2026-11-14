package com.soma.wes.agent.exception

import com.soma.wes.global.exception.BusinessException

class AgentException(errorCode: AgentErrorCode) : BusinessException(errorCode)
