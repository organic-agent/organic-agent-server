package com.soma.wes.folder.dto.response

import com.soma.wes.folder.domain.FolderOrigin
import com.soma.wes.folder.domain.PhotoFolderGroup
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "부모폴더 하나와 그 아래 자식폴더 요약들. 부모는 사진을 직접 담지 않는다")
data class PhotoFolderGroupResponse(
    val groupId: Long,
    val name: String,

    @field:Schema(description = "MANUAL(사용자가 만듦) | AI(AI 폴더 생성이 만듦). 화면의 AI 배지 재료다")
    val origin: FolderOrigin,

    @field:Schema(description = "AI 세트 키. 한 번의 AI 폴더 생성이 만든 부모들은 이 값이 같다 — 세트 단위로 접거나 지운다. MANUAL이면 null")
    val analysisJobId: Long?,

    @field:Schema(description = "자식폴더 요약. 만든 순서대로 온다")
    val folders: List<PhotoFolderResponse>,

    val createdAt: ZonedDateTime?,
    val updatedAt: ZonedDateTime?,
) {

    companion object {
        fun of(group: PhotoFolderGroup, folders: List<PhotoFolderResponse>) = PhotoFolderGroupResponse(
            groupId = group.requiredId,
            name = group.name.value,
            origin = group.origin,
            analysisJobId = group.analysisJobId,
            folders = folders,
            createdAt = group.createdAt,
            updatedAt = group.updatedAt,
        )
    }
}
