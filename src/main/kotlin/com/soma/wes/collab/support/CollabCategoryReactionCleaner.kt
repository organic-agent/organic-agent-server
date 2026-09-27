package com.soma.wes.collab.support

import com.soma.wes.folder.service.port.FolderReactionCleaner
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import org.springframework.stereotype.Component

@Component
class CollabCategoryReactionCleaner(
    private val sessionRepository: CollabSessionRepository,
    private val likeRepository: CollabPhotoLikeRepository,
    private val commentRepository: CollabPhotoCommentRepository,
) : FolderReactionCleaner {
    override fun deleteForConceptExit(conceptFolderId: Long, photoIds: Collection<Long>) {
        if (photoIds.isEmpty()) return
        val session = sessionRepository.findByConceptFolderId(conceptFolderId) ?: return
        likeRepository.deleteAllByCollabSessionIdAndPhotoIdIn(session.requiredId, photoIds)
        commentRepository.deleteAllByCollabSessionIdAndPhotoIdIn(session.requiredId, photoIds)
    }

    override fun deleteForConcept(conceptFolderId: Long) {
        val session = sessionRepository.findByConceptFolderId(conceptFolderId) ?: return
        sessionRepository.delete(session)
    }
}
