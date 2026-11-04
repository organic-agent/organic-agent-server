package com.soma.wes.retouch.dto

import com.soma.wes.retouch.domain.RetouchPoint

data class RetouchPdfPhotoDto(
    val photoId: Long,
    val originalFileName: String,
    val previewKey: String,
    val requestText: String?,
    val points: List<RetouchPoint>,
)
