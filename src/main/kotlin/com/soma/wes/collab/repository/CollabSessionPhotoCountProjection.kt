package com.soma.wes.collab.repository

/** [CollabPhotoRepository.countByCollabSessionIdIn]의 한 줄. */
interface CollabSessionPhotoCountProjection {
    val collabSessionId: Long
    val count: Long
}
