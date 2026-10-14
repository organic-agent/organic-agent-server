package com.soma.wes.auth.dto

/** 권한 확인 캐시의 키 — 무엇(종류)의 몇 번(id)인가. */
data class AccessCacheKeyDto(
    val kind: Kind,
    val id: Long,
) {
    enum class Kind {
        /** 계정의 탈퇴·정지 여부([AccessStatusDto]). */
        USER_STATUS,

        /** 작업공간의 종류·개인 소유자(`WorkspaceAccessDto`). 만든 뒤 바뀌지 않는다. */
        WORKSPACE,

        /** 스튜디오가 정지되지 않았는가. */
        STUDIO_ACTIVE,
    }

    companion object {
        fun userStatus(userId: Long) = AccessCacheKeyDto(kind = Kind.USER_STATUS, id = userId)

        fun workspace(workspaceId: Long) = AccessCacheKeyDto(kind = Kind.WORKSPACE, id = workspaceId)

        fun studioActive(workspaceId: Long) = AccessCacheKeyDto(kind = Kind.STUDIO_ACTIVE, id = workspaceId)
    }
}
