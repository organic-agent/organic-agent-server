package com.soma.wes.album.dto.response

import com.soma.wes.album.domain.AlbumShareMode
import com.soma.wes.album.domain.AlbumStatus
import com.soma.wes.album.dto.AlbumLayoutDto
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AlbumResponse(
    val status: AlbumStatus,
    @field:Schema(description = "다음 저장·발송·제출·확정 요청에 그대로 보내는 버전", example = "4")
    val revision: Long,
    @field:Schema(description = "서버가 마지막 변경을 저장한 시각", example = "2026-11-11T16:35:30+09:00")
    val updatedAt: ZonedDateTime?,
    val album: AlbumLayoutDto,
    @field:Schema(description = "최근 발송 시각. 아직 보내지 않았으면 null이다.", example = "2026-11-11T16:30:00+09:00")
    val sentAt: ZonedDateTime?,
    @field:Schema(description = "최근 발송 방식. view는 발송 즉시 확정된 상태다.", example = "view")
    val shareMode: AlbumShareMode?,
    @field:Schema(description = "스튜디오에게 보여 주는 최근 발송본. 미발송 또는 고객 조회이면 null이다.")
    val sentAlbum: AlbumLayoutDto? = null,
    @field:Schema(description = "스튜디오가 검토할 고객 제출본. 제출 전이거나 고객 조회이면 null이다.")
    val returnedAlbum: AlbumLayoutDto? = null,
    @field:Schema(description = "발송본 대비 사진 배치나 크롭이 달라진 프레임 ID", example = """["fr1"]""")
    val changedFrameIds: List<String> = emptyList(),
)

data class AlbumPhotosResponse(
    @field:Schema(description = "서명한 보정본 주소의 유효 기간(초)", example = "900")
    val viewUrlTtlSeconds: Long,
    val photos: List<SelectedPhoto>,
) {
    data class SelectedPhoto(
        val photoId: Long,
        @field:Schema(description = "가장 최근 완료 회차에서 보낸 보정본 주소. 보낸 보정본이 없으면 null이다.")
        val resultUrl: String?,
    )
}
