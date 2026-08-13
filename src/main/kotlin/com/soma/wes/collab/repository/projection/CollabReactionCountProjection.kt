package com.soma.wes.collab.repository.projection

import com.soma.wes.collab.domain.CollabReaction

interface CollabReactionCountProjection {
    val collabPhotoId: Long
    val reaction: CollabReaction
    val count: Long
}