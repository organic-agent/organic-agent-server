package com.soma.wes.gallery.exception

import com.soma.wes.global.exception.BusinessException

class GalleryException(errorCode: GalleryErrorCode) : BusinessException(errorCode)
