package com.soma.wes.cluster.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.cluster.dto.response.PhotoClustersResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Cluster]", description = "유사도 기반 사진 묶음 조회 API")
interface PhotoClusterControllerDocs {

    @Operation(
        summary = "유사도로 묶인 사진 조회",
        description = """
            threshold 이상으로 닮은 사진끼리 한 묶음으로 돌려준다. 높일수록 잘게, 낮출수록 크게 묶인다.
            생략하면 서버 기본값을 쓰고, 실제로 쓰인 값은 응답의 threshold에 담겨 온다.

            묶음은 큰 것부터 온다. 각 묶음의 photos는 갤러리 노출 순서를 따르므로 첫 장을 대표로 쓰면 된다.
            혼자 남은 사진도 크기 1짜리 묶음으로 들어 있다.

            아직 임베딩이 없는 사진은 어느 묶음에도 들어가지 못하고 unclassified 수로만 나온다.
            0이 아니면 임베딩 실행이 끝나지 않은 것이다 — POST /galleries/{galleryId}/embeddings/run 참고.

            담당 작가와 초대받은 부부 양쪽이 볼 수 있다. 부부는 갤러리가 열려 있고 마감 전일 때만 가능하다.
            저장되는 값이 아니므로 값을 바꿔가며 여러 번 불러도 된다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "threshold가 0.0~1.0을 벗어남", content = []),
        ApiResponse(responseCode = "403", description = "갤러리와 무관한 사용자이거나, 부부인데 마감/미공개 갤러리", content = []),
        ApiResponse(responseCode = "404", description = "존재하지 않는 갤러리", content = []),
    )
    fun cluster(
        loginUser: LoginUser,
        galleryId: Long,
        @Parameter(description = "0.0~1.0의 코사인 유사도. 생략하면 서버 기본값", example = "0.9")
        threshold: Double?,
    ): ResponseEntity<PhotoClustersResponse>
}
