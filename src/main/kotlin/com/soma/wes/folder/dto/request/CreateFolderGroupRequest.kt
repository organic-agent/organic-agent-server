package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.PhotoFolderGroup
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@Schema(
    description = "부모폴더 생성 요청. 클러스터링 결과를 고정할 때는 folders에 묶음별 자식폴더를 " +
        "함께 실어 한 번에 만들고, 빈 부모만 만들 때는 folders를 비워 보낸다.",
)
data class CreateFolderGroupRequest(

    @field:NotBlank
    @field:Size(max = PhotoFolderGroup.MAX_NAME_LENGTH)
    @field:Schema(description = "부모폴더 이름", example = "본식")
    val name: String,

    @field:Valid
    @field:Schema(
        description = "함께 만들 자식폴더들. 클러스터 응답의 묶음을 그대로 옮겨오면 된다. " +
            "고정 시점의 목록이 그대로 저장되므로, 나중에 임계값을 바꿔도 폴더는 달라지지 않는다.",
    )
    val folders: List<FolderSeed> = emptyList(),
) {

    @Schema(description = "함께 만들 자식폴더 하나")
    data class FolderSeed(

        @field:NotBlank
        @field:Size(max = PhotoFolderGroup.MAX_NAME_LENGTH)
        @field:Schema(description = "자식폴더 이름", example = "묶음 1")
        val name: String,

        @field:NotEmpty
        @field:Schema(description = "자식폴더에 담을 사진 id")
        val photoIds: List<Long>,
    )
}
