package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.PhotoFolder
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(description = "클러스터 결과에 이름을 붙여 폴더로 저장하는 요청")
data class CreatePhotoFolderRequest(

    @field:NotBlank
    @field:Size(max = PhotoFolder.MAX_NAME_LENGTH)
    @field:Schema(description = "폴더 이름", example = "본식 - 신부 단독")
    val name: String,

    @field:NotEmpty
    @field:Schema(
        description = "폴더에 담을 사진 id. 클러스터 응답에서 그대로 옮겨오면 된다. " +
            "저장 시점의 목록이 그대로 고정되므로, 나중에 임계값을 바꿔도 이 폴더는 달라지지 않는다.",
    )
    val photoIds: List<Long>,
)
