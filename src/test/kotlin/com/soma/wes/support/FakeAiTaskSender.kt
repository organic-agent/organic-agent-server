package com.soma.wes.support

import com.soma.wes.analysis.dto.AiTaskDto
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.port.AiTaskSender
import java.util.Collections
import kotlin.reflect.KClass

/**
 * 테스트용 전송기 — 보낸 작업을 기록만 하고 아무것도 돌리지 않는다. Lambda가 DB에 쓰는 일은 테스트가 직접 흉내 낸다.
 * [available]로 설정 없음(503)을, [failNext]로 호출 실패(502)를 재현한다.
 */
class FakeAiTaskSender : AiTaskSender {

    /** 동시성 테스트(스윕 둘)가 같은 목록에 기록하므로 동기화한다. */
    val tasks: MutableList<AiTaskDto> = Collections.synchronizedList(mutableListOf())
    var available: Boolean = true
    var failNext: Boolean = false

    override fun isAvailable(task: KClass<out AiTaskDto>): Boolean = available

    override fun send(task: AiTaskDto) {
        if (failNext) {
            failNext = false
            throw AnalysisException(AnalysisErrorCode.AI_TASK_SEND_FAILED)
        }
        tasks += task
    }

    val embedTasks: List<AiTaskDto.Embed>
        get() = tasks.toList().filterIsInstance<AiTaskDto.Embed>()

    val scoreTasks: List<AiTaskDto.Score>
        get() = tasks.toList().filterIsInstance<AiTaskDto.Score>()

    val categorizeTasks: List<AiTaskDto.Categorize>
        get() = tasks.toList().filterIsInstance<AiTaskDto.Categorize>()

    val exactPhotoTasks: List<AiTaskDto.ExactPhoto>
        get() = tasks.toList().filterIsInstance<AiTaskDto.ExactPhoto>()

    /** 임베더에 보낸 사진 id 전부(갤러리 무관). */
    val embeddedPhotoIds: List<Long>
        get() = embedTasks.flatMap { it.photoIds }

    fun reset() {
        tasks.clear()
        available = true
        failNext = false
    }
}
