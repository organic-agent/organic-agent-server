package com.soma.wes.collab.repository.projection

interface CollabCommentCountProjection {
    val collabPhotoId: Long
    val count: Long
}