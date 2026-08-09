package com.soma.wes.studio.support

/**
 * PostgreSQL advisory lock에서 studio writer가 공유하는 키 namespace.
 *
 * DB identity인 양수 studio id의 sign bit를 켜 음수 영역으로 옮긴다. 원래 id와 충돌하지 않고
 * Java와 Python이 같은 64-bit 값을 계산할 수 있다. app의 짧은 writer는 transaction shared
 * lock, Embedding Lambda는 job 전체 session shared lock, 삭제 준비는 transaction exclusive
 * lock을 쓴다.
 */
object StudioWriteFence {

    fun key(studioId: Long): Long {
        require(studioId > 0) { "저장된 studio id만 write fence에 사용할 수 있습니다." }
        return studioId xor Long.MIN_VALUE
    }
}
