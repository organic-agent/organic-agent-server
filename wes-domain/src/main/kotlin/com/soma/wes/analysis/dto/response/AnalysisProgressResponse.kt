package com.soma.wes.analysis.dto.response

import com.soma.wes.photo.repository.projection.GalleryAnalysisProgress
import io.swagger.v3.oas.annotations.media.Schema

/** 갤러리의 사진별 진행 — `/photos/summary`와 같은 프로젝션에서 나온다. 잡 상태와 무관하게 지금 DB에 있는 값이다. */
@Schema(description = "분석 진행. 사진 수 기준이고 실패한 사진은 expected에서 빠진다.")
data class AnalysisProgressResponse(
    @field:Schema(description = "분석 대상 — 업로드가 끝났고 실패하지 않은 사진 수.", example = "7189")
    val expected: Long,
    @field:Schema(description = "대상 중 DINOv3 벡터가 있는 사진 수.", example = "7189")
    val embedded: Long,
    @field:Schema(description = "대상 중 CLIP 벡터(점수)까지 있는 사진 수.", example = "7000")
    val scored: Long,
    @field:Schema(description = "대상 중 백분위까지 있는 사진 수 — categorize가 끝난 사진.", example = "0")
    val categorized: Long,
    @field:Schema(description = "결정적으로 실패한 사진 수(photo_analysis.error). 사진 상세의 analysis.error에서 이유를 본다.", example = "2")
    val failed: Long,
) {

    companion object {

        fun from(progress: GalleryAnalysisProgress): AnalysisProgressResponse = AnalysisProgressResponse(
            expected = progress.expected,
            embedded = progress.embedded,
            scored = progress.scored,
            categorized = progress.categorized,
            failed = progress.failed,
        )
    }
}
