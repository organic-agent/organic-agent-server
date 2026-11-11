package com.soma.wes.album.exception

import com.soma.wes.global.exception.BusinessException

class AlbumException(errorCode: AlbumErrorCode) : BusinessException(errorCode)
