package com.soma.wes.photo.dto.response

import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "갤러리의 업로드·분석 진행 집계. 임베딩·점수는 비동기라 진행 상황을 이 값으로 확인한다.")
data class PhotoSummaryResponse(
    val total: Long,
    @field:Schema(description = "업로드 URL만 발급된 사진. S3에 아직 없을 수 있다.")
    val pending: Long,
    @field:Schema(description = "원본이 S3에 있는 사진.")
    val uploaded: Long,
    @field:Schema(description = "올라온 사진 중 임베딩(DINOv3 벡터·미리보기)까지 끝난 수.")
    val embedded: Long,
    @field:Schema(description = "올라온 사진 중 점수(CLIP·미학·기술)까지 끝난 수.")
    val scored: Long,
    @field:Schema(description = "올라온 사진 중 백분위·그룹(categorize)까지 끝난 수.")
    val categorized: Long,
    @field:Schema(description = "분석이 결정적으로 실패한 사진 수. 갤러리에는 보이지만 AI 대상에서 빠진다.")
    val failed: Long,
) {

    companion object {

        fun from(progress: GalleryAnalysisProgress): PhotoSummaryResponse = PhotoSummaryResponse(
            total = progress.total,
            pending = progress.pending,
            uploaded = progress.expected + progress.failed,
            embedded = progress.embedded,
            scored = progress.scored,
            categorized = progress.categorized,
            failed = progress.failed,
        )
    }
}
