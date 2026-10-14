package com.soma.wes.photo.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "처리된 사진 수")
data class PhotoCountResponse(val count: Int)
