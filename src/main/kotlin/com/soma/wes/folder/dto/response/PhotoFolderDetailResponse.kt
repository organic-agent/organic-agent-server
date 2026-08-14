package com.soma.wes.folder.dto.response

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

    @field:Schema(description = "폴더에 든 사진. 갤러리에서 정한 노출 순서를 따른다")
    val photos: List<PhotoResponse>,

    @field:Schema(description = "서명된 조회 URL의 남은 수명. 지나기 전에 다시 부르면 새 URL이 온다")
    val viewUrlTtlSeconds: Long,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(folder: PhotoFolder, photos: List<PhotoResponse>, viewUrlTtlSeconds: Long) =
            PhotoFolderDetailResponse(
                folderId = folder.requiredId,
                groupId = folder.groupId,
                name = folder.name,
                photos = photos,
                viewUrlTtlSeconds = viewUrlTtlSeconds,
                createdAt = folder.createdAt,
                updatedAt = folder.updatedAt,
            )
    }
}
