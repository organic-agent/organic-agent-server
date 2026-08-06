package com.soma.wes.cluster.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "유사도 임계값으로 묶은 클러스터 전체")
data class PhotoClustersResponse(

    @field:Schema(description = "이번 묶음에 실제로 쓰인 임계값. 요청에서 생략하면 서버 기본값이 들어온다")
    val threshold: Double,

    @field:Schema(description = "큰 묶음이 먼저 온다. 혼자 남은 사진도 크기 1짜리 묶음으로 들어 있다")
    val clusters: List<PhotoClusterResponse>,

    @field:Schema(
        description = "아직 임베딩이 없어 어느 묶음에도 들어가지 못한 사진 수. " +
            "0이 아니면 임베딩 실행이 끝나지 않았다는 뜻이다.",
    )
    val unclassified: Long,
)
