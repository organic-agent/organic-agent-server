package com.soma.wes.retouch.dto.request

data class MatchRetouchResultsRequest(val files: List<File>) {
    data class File(val filename: String, val contentType: String)
}
