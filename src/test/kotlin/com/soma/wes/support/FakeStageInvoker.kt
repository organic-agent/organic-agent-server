package com.soma.wes.support

import com.soma.wes.analysis.dto.StageCall
import com.soma.wes.analysis.exception.AnalysisErrorCode
import com.soma.wes.analysis.exception.AnalysisException
import com.soma.wes.analysis.service.StageInvoker
import java.util.Collections
import kotlin.reflect.KClass

/**
 * 테스트용 실행기 — 호출을 기록만 하고 아무것도 돌리지 않는다. Lambda가 DB에 쓰는 일은 테스트가 직접 흉내 낸다.
 * [available]로 설정 없음(503)을, [failNext]로 호출 실패(502)를 재현한다.
 */
class FakeStageInvoker : StageInvoker {

    /** 동시성 테스트(스윕 둘)가 같은 목록에 기록하므로 동기화한다. */
    val calls: MutableList<StageCall> = Collections.synchronizedList(mutableListOf())
    var available: Boolean = true
    var failNext: Boolean = false

    override fun isAvailable(call: KClass<out StageCall>): Boolean = available

    override fun invoke(call: StageCall) {
        if (failNext) {
            failNext = false
            throw AnalysisException(AnalysisErrorCode.STAGE_INVOCATION_FAILED)
        }
        calls += call
    }

    val embedCalls: List<StageCall.Embed>
        get() = calls.toList().filterIsInstance<StageCall.Embed>()

    val scoreCalls: List<StageCall.Score>
        get() = calls.toList().filterIsInstance<StageCall.Score>()

    val categorizeCalls: List<StageCall.Categorize>
        get() = calls.toList().filterIsInstance<StageCall.Categorize>()

    /** 임베더에 보낸 사진 id 전부(갤러리 무관). */
    val embeddedPhotoIds: List<Long>
        get() = embedCalls.flatMap { it.photoIds }

    fun reset() {
        calls.clear()
        available = true
        failNext = false
    }
}
