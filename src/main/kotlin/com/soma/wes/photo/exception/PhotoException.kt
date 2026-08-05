package com.soma.wes.photo.exception

import com.soma.wes.global.exception.BusinessException

class PhotoException(errorCode: PhotoErrorCode) : BusinessException(errorCode)
