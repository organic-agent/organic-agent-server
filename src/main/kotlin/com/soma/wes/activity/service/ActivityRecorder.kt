package com.soma.wes.activity.service

import com.soma.wes.activity.repository.ActivityRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 성공한 업무 변경과 같은 트랜잭션에서만 호출한다. 인가 실패나 rollback은 활동 시각도 남기지 않는다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ActivityRecorder(
    private val repository: ActivityRepository,
    private val clock: Clock,
) {
    fun recordGallery(galleryId: Long) = repository.recordGallery(galleryId, ZonedDateTime.now(clock))

    fun recordWorkspace(workspaceId: Long) = repository.recordWorkspace(workspaceId, ZonedDateTime.now(clock))
}
