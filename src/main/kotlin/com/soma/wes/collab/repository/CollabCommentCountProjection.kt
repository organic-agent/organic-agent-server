package com.soma.wes.collab.repository

/** [CollabPhotoCommentRepository.countByCollabPhotoIdIn]의 한 줄. */
interface CollabCommentCountProjection {
    val collabPhotoId: Long
    val count: Long
}
