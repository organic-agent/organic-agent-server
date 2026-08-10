package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabReaction

/** [CollabPhotoVoteRepository.countByReaction]의 한 줄. 사진 하나에 반응 종류만큼 나온다. */
interface CollabReactionCountProjection {
    val collabPhotoId: Long
    val reaction: CollabReaction
    val count: Long
}
