package com.soma.wes.folder.exception

import com.soma.wes.global.exception.BusinessException

class FolderException(errorCode: FolderErrorCode) : BusinessException(errorCode)
