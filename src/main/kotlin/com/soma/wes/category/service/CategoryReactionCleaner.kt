package com.soma.wes.category.service

interface CategoryReactionCleaner {
    fun deleteForConceptExit(conceptFolderId: Long, photoIds: Collection<Long>)
    fun deleteForConcept(conceptFolderId: Long)
}
