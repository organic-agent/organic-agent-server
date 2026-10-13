package com.soma.wes.auth.domain

/**
 * JWT의 `sub` 클레임. 사용자 식별자를 문자열로 나른다.
 */
@JvmInline
value class Subject(val value: String) {

    fun toUserId(): Long = value.toLong()

    companion object {
        fun from(userId: Long): Subject = Subject(userId.toString())
    }
}
