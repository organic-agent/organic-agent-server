package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "협업 세션 열기 요청")
data class OpenCollabSessionRequest(

    @field:Schema(
        description = "옛 요청 모양. scope의 CONCEPT_FOLDERS에 컨셉 하나를 보낸 것과 같다 — 그 컨셉의 지금 사진을 담고, " +
            "이후 컨셉이 바뀌어도 따라가지 않는다.",
        deprecated = true,
    )
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
    @field:Schema(
        description = "수동 공유폴더에 처음 담을 사진 id. 갤러리 최대 사진 수까지 한 번에 보낼 수 있다. " +
            "생략하면 빈 폴더를 만든다. conceptFolderId·scope와 함께 지정할 수 없다.",
    )
    val photoIds: List<Long> = emptyList(),

    @field:Schema(
        description = "사진 id 대신 범위로 담는다. 서버가 만드는 순간의 사진을 골라 한 번에 담는다. " +
            "공유폴더는 원본 폴더와 따로 살아서, 이후 그 폴더의 사진이 옮겨지거나 폴더가 지워져도 따라가지 않는다. " +
            "conceptFolderId·photoIds와 함께 지정할 수 없다.",
    )
    val scope: PhotoScope? = null,
) {

    @Schema(description = "공유폴더에 담을 사진 범위. 휴지통 사진과 업로드가 끝나지 않은 사진은 빠진다.")
    data class PhotoScope(
        @field:Schema(
            description = "ALL: 갤러리의 모든 사진, CONCEPT_FOLDERS: 컨셉 폴더(대분류)들의 사진, " +
                "DETAIL_FOLDERS: 세부 폴더(소분류)들의 사진, SESSIONS: 공유폴더들이 지금 보여 주는 사진",
        )
        val type: Type,

        @field:Schema(description = "type이 CONCEPT_FOLDERS일 때만 지정한다.")
        val conceptFolderIds: List<Long> = emptyList(),

        @field:Schema(description = "type이 DETAIL_FOLDERS일 때만 지정한다.")
        val detailFolderIds: List<Long> = emptyList(),

        @field:Schema(description = "type이 SESSIONS일 때만 지정한다. 같은 갤러리의 공유폴더 id.")
        val sessionIds: List<Long> = emptyList(),
    ) {
        enum class Type { ALL, CONCEPT_FOLDERS, DETAIL_FOLDERS, SESSIONS }

        companion object {
            /** 한 번에 묶을 수 있는 폴더 수. 갤러리의 폴더가 이보다 많을 일은 없다. */
            const val MAX_FOLDER_COUNT = 100
        }
    }
}
