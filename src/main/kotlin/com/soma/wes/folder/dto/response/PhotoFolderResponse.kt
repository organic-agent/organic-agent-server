package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.PhotoFolder
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "폴더 하나. 목록에서는 사진 없이 개수만 온다")
data class PhotoFolderResponse(
    val folderId: Long,
    val name: String,

    @field:Schema(description = "폴더에 든 사진 수")
    val photoCount: Long,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(folder: PhotoFolder, photoCount: Long) = PhotoFolderResponse(
            folderId = folder.requiredId,
            name = folder.name,
            photoCount = photoCount,
            createdAt = folder.createdAt,
            updatedAt = folder.updatedAt,
        )
    }
}
