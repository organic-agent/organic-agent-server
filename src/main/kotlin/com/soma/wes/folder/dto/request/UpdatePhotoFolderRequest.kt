package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderCategory
import com.soma.wes.folder.domain.FolderName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "자식폴더 수정 요청. 보낸 필드만 바뀐다 — 셋 다 생략하면 아무 일도 없다")
data class UpdatePhotoFolderRequest(

    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(description = "새 폴더 이름. 생략하면 그대로 둔다", example = "야외 정원 산책")
    val name: String? = null,

    @field:Schema(description = "피사체 카테고리. 생략하면 그대로 둔다 — 지우는 수단은 아직 없다", example = "COUPLE")
    val category: FolderCategory? = null,

    @field:Schema(description = "true를 보내면 확인 필요 배지를 끈다. 서버가 다시 켜지 않는다. false·생략은 무시된다", example = "true")
    val reviewed: Boolean? = null,
)
