package com.soma.wes.photo.dto.response

import com.soma.wes.photo.domain.PhotoMetadata
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(
    description = "사진의 촬영 정보(EXIF). 임베딩 Lambda가 원본을 디코딩할 때 함께 읽어 채운다. " +
        "필드마다 null일 수 있다 — 촬영 정보를 남기지 않는 파일(스크린샷·편집본)이 있고, " +
        "그때도 가로·세로와 바이트 크기는 채워진다.",
)
data class PhotoMetadataResponse(

    @field:Schema(
        description = "촬영 시각. 타임존이 없다 — EXIF에 오프셋이 없어서, 카메라가 적은 벽시계 그대로다. " +
            "업로드 시각(createdAt)과 달리 지역 시간으로 읽어야 한다.",
        example = "2026-05-16T14:32:10",
    )
    val takenAt: LocalDateTime?,

    val cameraMake: String?,
    val cameraModel: String?,

    @field:Schema(description = "셔터 속도. `1/200` 처럼 EXIF 표기 그대로다", example = "1/200")
    val exposureTime: String?,

    @field:Schema(description = "조리개값. 화면에는 f/ 를 붙여 쓴다", example = "2.8")
    val fNumber: Double?,

    val iso: Int?,

    @field:Schema(description = "원본의 가로 픽셀. EXIF 회전을 반영한, 사람이 보는 방향의 값이다")
    val width: Int?,

    val height: Int?,

    @field:Schema(description = "원본 파일 크기(바이트)")
    val byteSize: Long?,
) {

    companion object {

        /**
         * 아직 아무것도 채워지지 않았으면 null을 돌려준다.
         *
         * 빈 값만 가득한 객체를 내려주면 화면은 "촬영 정보" 칸을 열어놓고 빈 줄만 늘어놓게 된다.
         * 클라이언트가 볼 것은 이 필드의 존재 여부 하나이고, 왜 없는지(아직 임베딩 전인지,
         * 원본에 EXIF가 없는지)는 같은 응답의 `status`가 말한다.
         */
        fun from(metadata: PhotoMetadata?): PhotoMetadataResponse? {
            if (metadata == null || metadata.isEmpty) {
                return null
            }

            return PhotoMetadataResponse(
                takenAt = metadata.takenAt,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                exposureTime = metadata.exposureTime,
                fNumber = metadata.fNumber,
                iso = metadata.iso,
                width = metadata.width,
                height = metadata.height,
                byteSize = metadata.byteSize,
            )
        }
    }
}
