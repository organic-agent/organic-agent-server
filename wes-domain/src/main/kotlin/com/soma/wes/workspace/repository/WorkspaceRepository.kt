package com.soma.wes.workspace.repository

import com.soma.wes.workspace.domain.Workspace
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface WorkspaceRepository : JpaRepository<Workspace, Long> {
    fun findByPersonalOwnerUserId(userId: Long): Workspace?

    /** 작업공간 멤버를 바꾸는 쓰기(파트너·직원 초대 수락, 파트너 내보내기·나가기, 직원 내보내기, 스튜디오 삭제)와 스튜디오 초대 재발급·이름 변경이 잠근다 — 개인 작업공간은 갤러리가 여럿일 수 있어 갤러리 잠금만으로는 정원 확인과 멤버 추가가 엇갈린다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Workspace?
}
