package com.soma.wes.activity.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.activity.service.port.ActivityBuffer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.ZonedDateTime

// [REFACTOR-CONFIRM 2026-09-27] KDoc 보강 — 로그가 아니라 제품 화면이 읽는 데이터라는 점과 멱등성을 적었다.
/**
 * 갤러리·워크스페이스의 최근 활동 시각(`gallery_activity`, `workspace_activity`)을 올린다.
 *
 * 로그가 아니라 제품 데이터다 — `UserService`가 소속 목록의 `lastActivityAt`과 최근 활동순 정렬에 쓰고, 웹의 워크스페이스
 * 선택 화면("N분 전")과 nav 소속 칩이 그 값을 보여 준다. [recordGallery]는 그 갤러리의 워크스페이스 시각도 함께 올린다 —
 * 스튜디오 카드는 `gallery.updatedAt`을 보지 않으므로 부부의 활동이 작가 목록에 반영되는 길은 이것뿐이다.
 *
 * 성공한 업무 변경의 트랜잭션 안에서만 호출한다. 인가 실패나 rollback은 활동 시각도 남기지 않는다.
 * 기존 값과 `greatest()`로 합치는 UPSERT라 한 트랜잭션에서 여러 번 불려도 결과가 같다 — 호출자가 중복을 피하려 분기하지 않는다.
 *
 * [buffer]가 있으면(wes-api, `app.activity.buffer.enabled`) 바로 쓰지 않고 커밋 뒤에 메모장에 남긴다(#286 (f)) — 한 스튜디오의
 * 갤러리 여럿에서 동시에 쓰면 모든 쓰기가 작업공간 한 행을 기다렸다. 그때 시각은 [ActivityFlushService]가 몇 초 뒤에 쓴다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ActivityRecorder(
    private val repository: ActivityRepository,
    private val clock: Clock,
    private val buffer: ActivityBuffer?,
) {
    fun recordGallery(galleryId: Long) {
        val at = ZonedDateTime.now(clock)
        val buffer = buffer ?: return repository.recordGallery(galleryId, at)
        afterCommit { buffer.markGallery(galleryId, at) }
    }

    fun recordWorkspace(workspaceId: Long) {
        val at = ZonedDateTime.now(clock)
        val buffer = buffer ?: return repository.recordWorkspace(workspaceId, at)
        afterCommit { buffer.markWorkspace(workspaceId, at) }
    }

    private fun afterCommit(action: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }
}
