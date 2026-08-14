package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.photo.dto.response.PhotoResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "자식폴더 하나. 목록에서는 사진 전부 대신 대표 한 장과 개수만 온다")
data class PhotoFolderResponse(
    val folderId: Long,

    @field:Schema(description = "이 폴더가 속한 부모폴더 id")
    val groupId: Long,

    val name: String,

    @field:Schema(description = "폴더에 든 사진 수. 삭제된 사진은 세지 않는다")
    val photoCount: Long,

    @field:Schema(
        description = "카드에 띄울 대표 사진. 노출 순서가 가장 앞선 한 장이다. " +
            "폴더가 비었거나 담긴 사진이 모두 삭제됐으면 null이다.",
    )
    val coverPhoto: PhotoResponse?,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(folder: PhotoFolder, photoCount: Long, coverPhoto: PhotoResponse?) = PhotoFolderResponse(
            folderId = folder.requiredId,
            groupId = folder.groupId,
            name = folder.name,
            photoCount = photoCount,
            coverPhoto = coverPhoto,
            createdAt = folder.createdAt,
            updatedAt = folder.updatedAt,
        )
    }
}
