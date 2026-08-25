package com.soma.wes.admin.exception

import com.soma.wes.security.exception.CustomAuthenticationException

class AdminAuthenticationException(
    errorCode: AdminErrorCode,
) : CustomAuthenticationException(errorCode)
