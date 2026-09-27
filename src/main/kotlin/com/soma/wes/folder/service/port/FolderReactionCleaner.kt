package com.soma.wes.folder.service.port

interface FolderReactionCleaner {
    fun deleteForConceptExit(conceptFolderId: Long, photoIds: Collection<Long>)
    fun deleteForConcept(conceptFolderId: Long)
}
