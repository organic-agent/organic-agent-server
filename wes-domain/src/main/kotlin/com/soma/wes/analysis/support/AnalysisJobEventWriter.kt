package com.soma.wes.analysis.support

import com.soma.wes.analysis.domain.AnalysisJobEvent
import com.soma.wes.analysis.dto.AnalysisJobEventDto
import com.soma.wes.analysis.repository.AnalysisJobEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 이력 한 줄을 자기 트랜잭션으로 쓴다. [AnalysisJobEventListener]의 이력 스레드만 부른다. */
@Service
class AnalysisJobEventWriter(
    private val eventRepository: AnalysisJobEventRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun write(event: AnalysisJobEventDto) {
        eventRepository.save(
            AnalysisJobEvent(
                jobId = event.jobId,
                galleryId = event.galleryId,
                type = event.type,
                detail = event.detail,
                createdAt = event.createdAt,
            ),
        )
    }
}
