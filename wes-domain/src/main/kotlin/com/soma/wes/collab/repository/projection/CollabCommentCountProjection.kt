package com.soma.wes.collab.repository.projection

interface CollabCommentCountProjection {
    val photoId: Long
    val count: Long
}
