package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * 부모폴더와 자식폴더가 공유하는 이름. 두 이름은 한 트리에서 나란히 보이고 같은 에러 코드로
 * 거절되는 하나의 규칙이라, 값 하나로 묶어 규칙과 길이를 한 곳에 둔다.
 *
 * 검증은 생성자가 아니라 [of]에 있다 — JPA가 DB에서 되살릴 때도 생성을 지나므로, 생성자에서
 * 검증하면 어쩌다 잘못 들어간 행 하나가 폴더 목록 조회 전체를 실패시킨다([PhotoRating]과 같은
 * 이유다). 애플리케이션이 이름을 만드는 관문은 [of] 하나뿐이다.
 */
@Embeddable
class FolderName(

    @Column(name = "name", nullable = false, length = MAX_LENGTH)
    val value: String,
) {

    override fun equals(other: Any?): Boolean = other is FolderName && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

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
