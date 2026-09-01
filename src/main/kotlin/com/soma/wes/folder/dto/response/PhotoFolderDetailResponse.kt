package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.FolderCategory
import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "자식폴더 하나와 그 안의 사진 전부")
data class PhotoFolderDetailResponse(
    val folderId: Long,

    @field:Schema(description = "이 폴더가 속한 부모폴더 id")
    val groupId: Long,

    val name: String,

    @field:Schema(description = "피사체 카테고리 칩. null이면 없음")
    val category: FolderCategory?,

    @field:Schema(description = "AI 배정의 확신이 낮아 확인이 필요하다는 배지")
    val needsReview: Boolean,

    @field:Schema(description = "폴더에 든 사진. 앨범 sortOrder를 우선하고 같은 값은 갤러리 노출 순서를 따른다")
    val photos: List<PhotoResponse>,

    @field:Schema(description = "앨범 목업 순서와 크롭을 포함한 사진 항목. photos와 같은 순서다")
    val items: List<PhotoFolderItemResponse>,

    @field:Schema(description = "서명된 조회 URL의 남은 수명. 지나기 전에 다시 부르면 새 URL이 온다")
    val viewUrlTtlSeconds: Long,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(
            folder: PhotoFolder,
            photos: List<PhotoResponse>,
            items: List<PhotoFolderItemResponse>,
            viewUrlTtlSeconds: Long,
        ) =
            PhotoFolderDetailResponse(
                folderId = folder.requiredId,
                groupId = folder.groupId,
                name = folder.name.value,
                category = folder.category,
                needsReview = folder.needsReview,
                photos = photos,
                items = items,
                viewUrlTtlSeconds = viewUrlTtlSeconds,
                createdAt = folder.createdAt,
                updatedAt = folder.updatedAt,
            )
    }
}

data class PhotoFolderItemResponse(
    val photo: PhotoResponse,
    val sortOrder: Int,
    val crop: Map<String, Any?>?,
)
