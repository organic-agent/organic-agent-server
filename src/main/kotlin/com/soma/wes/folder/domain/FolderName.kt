package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException

/**
 * 부모폴더와 자식폴더가 공유하는 이름 규칙.
 *
 * 두 이름은 한 트리에서 나란히 보이고 같은 에러 코드로 거절되는 하나의 규칙이다. 엔티티마다
 * 복사해 두면 한쪽만 고쳤을 때 같은 화면의 두 입력이 다르게 동작하는데, 컴파일도 테스트도
 * 조용히 통과한다.
 */
object FolderName {

    const val MAX_LENGTH = 100

    fun normalize(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) {
            throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
        }
        return trimmed
    }
}
