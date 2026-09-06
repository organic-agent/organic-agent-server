package com.soma.wes.retouch.dto.response

data class MatchRetouchResultsResponse(val matches: List<Match>) {
    data class Candidate(val photoId: Long, val filename: String)
    data class Match(
        val filename: String,
        val contentType: String,
        val photoId: Long?,
        val candidates: List<Candidate>,
    )
}
