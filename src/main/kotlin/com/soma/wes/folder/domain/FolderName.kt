package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException

/** 컨셉 · 세부 폴더 이름 규칙. 만들 때와 바꿀 때가 같은 규칙을 지난다. */
object FolderName {
    const val MAX_LENGTH = 100

    /** 앞뒤 공백을 지운 이름을 돌려준다. 비었거나 너무 길면 거절한다. */
    fun requireValid(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) {
            throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
        }
        return trimmed
    }
}
