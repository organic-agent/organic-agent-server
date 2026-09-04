package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 부모폴더와 자식폴더가 공유하는 이름.
 */
@Embeddable
data class FolderName(

    @Column(name = "name", nullable = false, length = MAX_LENGTH)
    val value: String,
) {

    companion object {
        const val MAX_LENGTH = 100

        fun of(name: String): FolderName {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) {
                throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
            }
            return FolderName(trimmed)
        }
    }
}
