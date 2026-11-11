package com.soma.wes.album.support

import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Component

@Component
class AlbumPhotoLoader(
    private val selections: PhotoSelectionRepository,
    private val selectionItems: PhotoSelectionItemRepository,
    private val photos: PhotoRepository,
) {
    /** 원본이 남아 있고 선택에서도 빠지지 않은 사진만 선택 앨범 순서대로 반환한다. */
    fun findSelectedIds(galleryId: Long): Set<Long> {
        val selection = selections.findByGalleryId(galleryId) ?: return emptySet()
        val items = selectionItems.findAllBySelectionId(selection.requiredId)
        if (items.isEmpty()) return emptySet()

        val available = photos.findAllByGalleryIdAndIdInAndExcludedAtIsNull(galleryId, items.map { it.photoId })
            .filter { it.status == PhotoStatus.UPLOADED }
            .map { it.requiredId }
            .toSet()
        val ordered = items.sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
        return ordered.map { it.photoId }.filter { it in available }.toSet()
    }
}
