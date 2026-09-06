package com.soma.wes.retouch.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.exception.RetouchException

/** 원본 표시 크기가 달라도 같은 지점을 가리키도록 좌표를 0~1로 정규화한다. */
data class RetouchPoint @JsonCreator constructor(
    @param:JsonProperty(value = "x", required = true) val x: Double,
    @param:JsonProperty(value = "y", required = true) val y: Double,
    @param:JsonProperty(value = "text", required = true) val text: String,
    @param:JsonProperty("refinedText") val refinedText: String? = null,
    @param:JsonProperty("useRefinedText") val useRefinedText: Boolean = false,
) {
    fun validate() {
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0 ||
            text.isBlank() || text.length > RetouchPhoto.MAX_REQUEST_TEXT_LENGTH ||
            (refinedText != null && refinedText.length > RetouchPhoto.MAX_REQUEST_TEXT_LENGTH) ||
            (useRefinedText && refinedText.isNullOrBlank())
        ) {
            throw RetouchException(RetouchErrorCode.INVALID_POINT)
        }
    }
}
