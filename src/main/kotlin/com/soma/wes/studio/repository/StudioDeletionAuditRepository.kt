package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioDeletionAudit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * 삭제 후에도 남아야 하는 감사 기록을 관리한다.
 *
 * 스튜디오와 감사 기록은 서로 다른 테이블과 생명주기를 가지며, 삭제된 스튜디오를 다시 조회할 수
 * 없을 때도 요청의 멱등성을 확인해야 하므로 [StudioRepository]와 분리한다.
 */
interface StudioDeletionAuditRepository : JpaRepository<StudioDeletionAudit, Long> {

    fun findByRequestId(requestId: UUID): StudioDeletionAudit?
}
