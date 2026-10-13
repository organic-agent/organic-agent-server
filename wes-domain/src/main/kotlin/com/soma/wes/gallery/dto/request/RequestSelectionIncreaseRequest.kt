package com.soma.wes.gallery.dto.request

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

data class RequestSelectionIncreaseRequest(
    @field:Min(1) val requestedCount: Int,
    @field:Size(max = 300) val message: String? = null,
)
