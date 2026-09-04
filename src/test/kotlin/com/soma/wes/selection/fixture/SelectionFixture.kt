package com.soma.wes.selection.fixture

import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.domain.PhotoSelectionItem
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Component

@Component
class SelectionFixture(
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
) {

    /** 갤러리의 선택 앨범 행. 정상 경로에서는 부부가 처음 담을 때 생기는 것을 배경으로 미리 만든다. */
    fun 셀렉(galleryId: Long): Long =
        (photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))).requiredId

    /** 원본으로 담은 항목. 정원·중복 검사를 지나지 않으므로 배경으로만 쓴다. */
    fun 담긴_사진(selectionId: Long, photoIds: Collection<Long>) {
        val selection = photoSelectionRepository.findById(selectionId).orElseThrow()
        photoSelectionItemRepository.saveAll(
            photoIds.mapIndexed { index, photoId ->
                PhotoSelectionItem(
                    galleryId = selection.galleryId,
                    selectionId = selectionId,
                    photoId = photoId,
                    addedByUserId = null,
                    sortOrder = index,
                )
            },
        )
    }
}
