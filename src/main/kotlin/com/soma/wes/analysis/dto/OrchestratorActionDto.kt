package com.soma.wes.analysis.dto

/**
 * 분석 잡의 한 걸음(관측 + 전이 트랜잭션)이 끝난 뒤 트랜잭션 밖에서 할 일 하나. 잡은 Lambda를 categorize 한 번만
 * 부르므로 [Invoke]는 단건이다 — 임베더 배정과 score 폴백은 잡과 무관하게 스윕이 따로 한다.
 */
sealed interface OrchestratorActionDto {

    val jobId: Long

    /** categorize EVENT 한 번. 호출이 실패하면 잡의 전송 시각을 지워 다음 스윕이 다시 보내게 한다. */
    data class Invoke(
        override val jobId: Long,
        val call: StageCallDto.Categorize,
    ) : OrchestratorActionDto

    /** 배정이 다 왔으니 AI 폴더를 물질화하고 잡을 닫는다. */
    data class Materialize(
        override val jobId: Long,
        val galleryId: Long,
    ) : OrchestratorActionDto
}
