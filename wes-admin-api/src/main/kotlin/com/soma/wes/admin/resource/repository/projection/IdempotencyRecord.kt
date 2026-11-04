package com.soma.wes.admin.resource.repository.projection


data class IdempotencyRecord(
    val requestHash: String,
    val status: String,
    val resultPayload: String?,
)
