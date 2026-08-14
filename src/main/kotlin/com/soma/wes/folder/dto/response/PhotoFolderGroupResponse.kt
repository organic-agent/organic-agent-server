package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.PhotoFolderGroup
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "부모폴더 하나와 그 아래 자식폴더 요약들. 부모는 사진을 직접 담지 않는다")
data class PhotoFolderGroupResponse(
    val groupId: Long,
    val name: String,

    @field:Schema(description = "자식폴더 요약. 만든 순서대로 온다")
    val folders: List<PhotoFolderResponse>,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(group: PhotoFolderGroup, folders: List<PhotoFolderResponse>) = PhotoFolderGroupResponse(
            groupId = group.requiredId,
            name = group.name,
            folders = folders,
            createdAt = group.createdAt,
            updatedAt = group.updatedAt,
        )
    }
}
