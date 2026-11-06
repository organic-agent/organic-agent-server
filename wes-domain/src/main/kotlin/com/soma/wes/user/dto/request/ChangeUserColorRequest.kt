package com.soma.wes.user.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class ChangeUserColorRequest(
    @field:Schema(
        description = "웹이 정한 색 키(소문자). null을 보내면 고른 색을 지우고 웹이 기본 색을 준다.",
        example = "sky",
        allowableValues = ["sky", "coral", "apricot", "lemon", "sage", "mint", "rose"],
        nullable = true,
    )
    val color: String? = null,
)
