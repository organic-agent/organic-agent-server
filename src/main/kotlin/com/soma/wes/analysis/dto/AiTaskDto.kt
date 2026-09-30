package com.soma.wes.analysis.dto

/**
 * 외부 실행기(Lambda 또는 로컬 서브프로세스)에 보내는 AI 작업 요청 하나. 트랜잭션 안에서 정한 "무엇을 부를지"를
 * 트랜잭션 밖의 호출로 넘기는 전달용 값이기도 하다. 페이로드 키는 AI repo 계약 그대로다(계획서 §7) — 각 하위 타입의
 * 프로퍼티 이름이 곧 JSON 키라 이름을 바꾸면 계약이 깨진다.
 */
sealed interface AiTaskDto {

    val galleryId: Long

    /** embedder — 배정된 사진 목록만 미리보기·DINOv3 벡터·EXIF. 잡을 모른다. */
    data class Embed(
        override val galleryId: Long,
        val photoIds: List<Long>,
    ) : AiTaskDto

    /** score Lambda 폴백 — GPU 워커가 없을 때 미점수 사진 목록만 CLIP·점수·피사체. 페이로드가 [Embed]와 같은 모양이다. */
    data class Score(
        override val galleryId: Long,
        val photoIds: List<Long>,
    ) : AiTaskDto

    /** categorize — 갤러리 전체의 백분위·연사·그룹 + Bedrock 이름·배정(`concept_assignments(job_id)`). */
    data class Categorize(
        override val galleryId: Long,
        val jobId: Long,
    ) : AiTaskDto

    /**
     * embedder — 관리자 사진 교체 뒤 정확히 한 리비전의 한 사진. [Embed]와 같은 함수를 부르지만 `jobId` 키가 있어 임베더가
     * 관리자 사진 교체 이벤트로 해석한다. 로컬 대역 스크립트는 이 호출을 지원하지 않는다.
     */
    data class ExactPhoto(
        val jobId: Long,
        val attemptCount: Int,
        val jobType: String,
        val photoId: Long,
        override val galleryId: Long,
        val storageKey: String,
        val revisionId: Long,
    ) : AiTaskDto
}
