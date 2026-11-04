package com.soma.wes.admin.resource.dto.request

import com.soma.wes.admin.resource.domain.AdminPhotoAccessMode
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactAccessMode
import io.swagger.v3.oas.annotations.media.Schema

data class AdminPhotoAccessRequest(
    @field:Schema(
        description = "발급할 서명 URL 종류. PREVIEW는 미리보기(없으면 원본으로 대신하지 않고 404), VIEW는 원본 보기, DOWNLOAD는 원본 파일명으로 내려받기다. PREVIEW는 보기 URL TTL, VIEW·DOWNLOAD는 원본 URL TTL 동안 유효하다.",
        example = "VIEW",
    )
    val mode: AdminPhotoAccessMode,
)

data class AdminRetouchArtifactAccessRequest(
    @field:Schema(
        description = "발급할 서명 URL 종류. VIEW는 보기용(보기 URL TTL), DOWNLOAD는 원본 파일명으로 내려받기용(원본 URL TTL)이다.",
        example = "DOWNLOAD",
    )
    val mode: AdminRetouchArtifactAccessMode,
)
