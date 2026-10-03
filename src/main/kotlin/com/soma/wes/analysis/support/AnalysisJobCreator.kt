package com.soma.wes.analysis.support

import com.soma.wes.analysis.config.AnalysisProperties
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.domain.AnalysisTrigger
import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.analysis.service.port.AiTaskSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 분석 잡 행을 만드는 한 곳. 분석 요청 API 와 서버의 자동 생성·자동 재시도가 모두 여기를 지난다.
 *
 * 갤러리당 살아 있는 잡이 하나라는 규칙은 DB 의 부분 유니크(`uk_analysis_jobs_active`)가 지킨다. 둘이 동시에 만들면 늦은 쪽이
 * `DataIntegrityViolationException` 을 받는다 — 그래서 **자기 트랜잭션**이다. Postgres 는 충돌이 난 트랜잭션을 이어 쓸 수 없어,
 * 호출자의 트랜잭션 안에서 만들면 충돌 뒤에 "이미 있는 잡"을 다시 읽을 수 없고, 스윕이 여러 갤러리를 한 트랜잭션으로 돌면
 * 한 갤러리의 충돌이 나머지를 같이 되돌린다. 충돌을 어떻게 받을지는 호출자가 정한다.
 */
@Service
class AnalysisJobCreator(
    private val analysisJobRepository: AnalysisJobRepository,
    private val aiTaskSender: AiTaskSender,
    private val eventRecorder: AnalysisJobEventRecorder,
    private val properties: AnalysisProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 잡을 끝까지 밀 실행기가 설정돼 있는가. 로컬·테스트에는 실행기가 없는 것이 정상이라 기동이 아니라 잡을 만들 때 본다 —
     * 없는데 만들면 잡이 영영 ANALYZING 에 머문다.
     */
    val isRunnable: Boolean
        get() {
            val required = listOf(AiTaskDto.Embed::class, AiTaskDto.Categorize::class) +
                if (properties.gpu.enabled) emptyList() else listOf(AiTaskDto.Score::class)
            return required.all { aiTaskSender.isAvailable(it) }
        }

    /** [expected]는 로그에만 쓴다 — 만든 시점의 분석 대상 장수다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(
        galleryId: Long,
        conceptCount: Int?,
        trigger: AnalysisTrigger,
        retryCount: Int,
        expected: Long,
    ): AnalysisJob {
        val job = analysisJobRepository.saveAndFlush(
            AnalysisJob(galleryId = galleryId, conceptCount = conceptCount, trigger = trigger, retryCount = retryCount),
        )
        log.info(
            "event=job.created job={} gallery={} trigger={} retry={} expected={} conceptCount={}",
            job.requiredId, galleryId, trigger, retryCount, expected, conceptCount,
        )
        eventRecorder.record(
            job.requiredId, galleryId, AnalysisJobEventType.CREATED,
            mapOf("trigger" to trigger.name, "retry" to retryCount, "expected" to expected, "conceptCount" to conceptCount),
        )
        return job
    }
}
