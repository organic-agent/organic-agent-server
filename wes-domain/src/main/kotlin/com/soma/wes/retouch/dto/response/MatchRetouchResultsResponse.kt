package com.soma.wes.retouch.dto.response

import io.swagger.v3.oas.annotations.media.Schema

data class MatchRetouchResultsResponse(val matches: List<Match>) {
    data class Candidate(val photoId: Long, val filename: String)
    data class Match(
        val filename: String,
        val contentType: String,
        @field:Schema(
            description = "확장자를 뺀 파일 이름이 대소문자 구분 없이 회차 사진 하나와만 맞을 때 그 사진 id. 맞는 사진이 없거나 여럿이면 null이고, candidates에서 골라야 한다.",
            example = "1024",
        )
        val photoId: Long?,
        val candidates: List<Candidate>,
    )
}
