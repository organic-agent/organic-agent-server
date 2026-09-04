package com.soma.wes.folder.fixture

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderGroup
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import org.springframework.stereotype.Component

@Component
class FolderFixture(
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
) {

    /** 부부가 확정한 사진 묶음. 협업 세션이 이것을 복사해 채운다. */
    fun 확정된_폴더(galleryId: Long, name: String, photoIds: List<Long>): Long {
        val group = photoFolderGroupRepository.save(PhotoFolderGroup.of(galleryId, name))
        val folder = photoFolderRepository.save(PhotoFolder.of(group, name))
        photoFolderItemRepository.saveAll(
            photoIds.map {
                PhotoFolderItem(groupId = group.requiredId, folderId = folder.requiredId, photoId = it)
            },
        )
        return folder.requiredId
    }
}
