package com.soma.wes.retouch.dto.response

data class RefineRetouchResponse(
    val originalText: String,
    val refinedText: String?,
    val available: Boolean,
)
