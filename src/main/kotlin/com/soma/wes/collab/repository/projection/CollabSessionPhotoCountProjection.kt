package com.soma.wes.collab.repository.projection

interface CollabSessionPhotoCountProjection {
    val collabSessionId: Long
    val count: Long
}