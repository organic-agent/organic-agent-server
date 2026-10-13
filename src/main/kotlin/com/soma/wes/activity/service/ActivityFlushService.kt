package com.soma.wes.activity.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.activity.service.port.ActivityBuffer
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.ZonedDateTime

/**
 * 메모장에 모인 활동 시각을 DB에 쓴다(#286 (f)).
 *
 * 트랜잭션으로 묶지 않고 문장 하나씩 쓴다 — 여러 작업공간 행을 한 트랜잭션에 쥐지 않아 서버가 여럿이어도 교착이 없고,
 * 하나가 실패해도 나머지는 쓰인다. 실패한 시각은 메모장에 되돌려 다음 주기에 다시 쓴다(`greatest()`라 늦게 써도 같다).
 */
@Service
@ConditionalOnProperty(prefix = "app.activity.buffer", name = ["enabled"], havingValue = "true")
class ActivityFlushService(
    private val buffer: ActivityBuffer,
    private val repository: ActivityRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 쓴 행 수를 돌려준다. 실패한 것은 세지 않는다. */
    fun flush(): Int {
        val marks = buffer.drain()
        if (marks.isEmpty) return 0

        val galleries = marks.galleries.entries.sortedBy { it.key }
            .count { (galleryId, at) -> write(galleryId, at, repository::recordGallery, buffer::markGallery) }
        val workspaces = marks.workspaces.entries.sortedBy { it.key }
            .count { (workspaceId, at) -> write(workspaceId, at, repository::recordWorkspace, buffer::markWorkspace) }

        val failed = marks.galleries.size + marks.workspaces.size - galleries - workspaces
        if (failed > 0) log.warn("event=activity.flush.retry failed={} — 다음 주기에 다시 쓴다", failed)
        return galleries + workspaces
    }

    private fun write(
        id: Long,
        at: ZonedDateTime,
        record: (Long, ZonedDateTime) -> Unit,
        putBack: (Long, ZonedDateTime) -> Unit,
    ): Boolean = try {
        record(id, at)
        true
    } catch (e: RuntimeException) {
        log.debug("event=activity.flush.failed id={}", id, e)
        putBack(id, at)
        false
    }
}
