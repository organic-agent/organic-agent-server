package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "부모폴더 아래 자식폴더를 만드는 요청")
data class CreatePhotoFolderRequest(

    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(description = "폴더 이름", example = "본식 - 신부 단독")
    val name: String,

    @field:Schema(
        description = "폴더에 담을 사진 id. 비워 보내면 빈 폴더가 만들어진다 — " +
            "드래그로 채워 넣는 흐름이 빈 폴더에서 시작한다.",
    )
    val photoIds: List<Long> = emptyList(),
)
