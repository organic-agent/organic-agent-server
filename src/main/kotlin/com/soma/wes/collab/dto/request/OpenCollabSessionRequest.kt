package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 열기 요청")
data class OpenCollabSessionRequest(

    @field:Schema(description = "기존 컨셉 공유 호환용. 생략하면 사진을 직접 담는 공유폴더를 만든다.")
    val conceptFolderId: Long? = null,

    @field:Schema(
        description = "부부가 링크를 구분하려고 붙이는 이름. 하객 첫 화면에도 보인다. " +
            "같은 컨셉으로 다시 열면 기존 세션의 이름이 이 값으로 갱신된다.",
        example = "본식 후보",
    )
    val name: String,
    val coverTitle: String? = null,
    val coverAuthor: String? = null,
    val includeAllAlbums: Boolean? = null,
    @field:jakarta.validation.constraints.Size(max = CollabPhotoIdsRequest.MAX_BATCH_SIZE)
    @field:Schema(description = "수동 공유폴더에 처음 담을 사진 id. 생략하면 빈 폴더를 만든다. 컨셉 연결과 함께 지정할 수 없다.")
    val photoIds: List<Long> = emptyList(),

)
