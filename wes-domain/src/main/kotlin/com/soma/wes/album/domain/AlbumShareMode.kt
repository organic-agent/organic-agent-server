package com.soma.wes.album.domain

import com.fasterxml.jackson.annotation.JsonProperty

enum class AlbumShareMode {
    @JsonProperty("view")
    VIEW,

    @JsonProperty("edit")
    EDIT,
}
