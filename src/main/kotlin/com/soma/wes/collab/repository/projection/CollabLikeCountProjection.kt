package com.soma.wes.collab.repository.projection

interface CollabLikeCountProjection {
    val collabPhotoId: Long
    val count: Long
}
