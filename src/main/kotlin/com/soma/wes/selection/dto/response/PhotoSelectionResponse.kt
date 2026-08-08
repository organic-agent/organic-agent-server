package com.soma.wes.selection.dto.response

import com.soma.wes.photo.dto.response.PhotoResponse
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.domain.PhotoSelectionStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "갤러리의 선택 앨범. 부부가 최종적으로 고른 사진 전부와 계약 장수를 담는다")
data class PhotoSelectionResponse(

    @field:Schema(description = "아직 고르는 중이면 SELECTING, 작가에게 넘어갔으면 SUBMITTED")
    val status: PhotoSelectionStatus,

    @field:Schema(description = "계약한 선택 장수. null이면 제한이 없다")
    val targetPhotoCount: Int?,

    @field:Schema(description = "지금까지 고른 장수")
    val selectedCount: Int,

    @field:Schema(
        description = "더 고를 수 있는 장수. 제한이 없으면 null이고, 계약 장수가 줄어 이미 넘겼다면 0이다",
    )
    val remainingCount: Int?,

    val submittedAt: ZonedDateTime?,

    @field:Schema(description = "고른 사진. 갤러리에서 정한 노출 순서를 따른다")
    val photos: List<PhotoResponse>,

    @field:Schema(description = "서명된 조회 URL의 남은 수명. 지나기 전에 다시 부르면 새 URL이 온다")
    val viewUrlTtlSeconds: Long,
) {

    companion object {

        /**
         * `selection`이 null이면 아직 한 장도 담지 않아 앨범 행이 없는 갤러리다.
         *
         * 조회가 행을 만들지 않게 하려고 그 경우를 여기서 흡수한다 — 읽기만 하는 요청이
         * 쓰기 트랜잭션을 잡으면, 갤러리를 열어보기만 한 사람 수만큼 빈 앨범이 쌓인다.
         * 화면에서는 "0장 고름"과 구분되지 않아야 하므로 SELECTING인 빈 앨범으로 보인다.
         */
        fun of(
            selection: PhotoSelection?,
            targetPhotoCount: Int?,
            photos: List<PhotoResponse>,
            viewUrlTtlSeconds: Long,
        ) = PhotoSelectionResponse(
            status = selection?.status ?: PhotoSelectionStatus.SELECTING,
            targetPhotoCount = targetPhotoCount,
            selectedCount = photos.size,
            remainingCount = targetPhotoCount?.let { (it - photos.size).coerceAtLeast(0) },
            submittedAt = selection?.submittedAt,
            photos = photos,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
        )
    }
}
