package com.soma.wes.folder.support

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderItemResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Component

/**
 * 자식폴더를 화면이 그릴 수 있는 응답으로 만든다. 부모·자식 서비스가 함께 쓴다.
 */
@Component
class FolderViewAssembler(
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
) {

    /**
     * 여러 자식폴더의 요약을 한 번에 만든다. 폴더마다 질의를 돌리면 목록 길이만큼 늘어나므로
     * 항목과 사진을 한 번씩만 읽어 나눈다. 응답은 folderId로 찾는다.
     */
    fun summariesByFolderId(folders: List<PhotoFolder>): Map<Long, PhotoFolderResponse> {
        if (folders.isEmpty()) {
            return emptyMap()
        }

        val items = photoFolderItemRepository.findAllByFolderIdInOrderBySortOrderAscIdAsc(
            folders.map { it.requiredId },
        )
        val alivePhotos = existingPhotos(folders.first().galleryId, items.map { it.photoId })
        val photosByFolderId = items.mapNotNull { item ->
            alivePhotos[item.photoId]?.let { ItemPhoto(item, it) }
        }.groupBy { row -> row.item.folderId }

        // 상세 조회와 같은 정렬이라 카드의 대표 사진과 팝업의 첫 장이 어긋나지 않는다.
        val photosByFolder = folders.associateWith {
            photosByFolderId[it.requiredId].orEmpty()
                .sortedWith(ITEM_DISPLAY_ORDER)
                .map(ItemPhoto::photo)
        }

        // 대표 사진도 한 번에 응답으로 만든다. 폴더마다 toResponse를 부르면 사진에 붙는 별점 조회가 폴더 수만큼 늘어난다.
        val covers = photoViewAssembler.toResponses(photosByFolder.values.mapNotNull { it.firstOrNull() })
            .associateBy { it.photoId }

        return photosByFolder.entries.associate { (folder, photos) ->
            folder.requiredId to PhotoFolderResponse.of(
                folder = folder,
                photoCount = photos.size.toLong(),
                coverPhoto = photos.firstOrNull()?.let { covers[it.requiredId] },
            )
        }
    }

    /** [summariesByFolderId]가 지워진 사진을 세지 않으려고 쓴다. 폴더 수와 무관하게 질의는 하나다. */
    private fun existingPhotos(galleryId: Long, photoIds: List<Long>): Map<Long, Photo> {
        if (photoIds.isEmpty()) {
            return emptyMap()
        }
        return photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds.toSet())
            .associateBy { it.requiredId }
    }

    /** 목록용 응답 한 장. 여러 폴더는 [summariesByFolderId]로 한 번에 만든다. */
    fun summaryOf(folder: PhotoFolder): PhotoFolderResponse {
        val photos = loadPhotosOf(folder)
        return PhotoFolderResponse.of(
            folder = folder,
            photoCount = photos.size.toLong(),
            coverPhoto = photos.firstOrNull()?.let(photoViewAssembler::toResponse),
        )
    }

    fun detailOf(folder: PhotoFolder): PhotoFolderDetailResponse {
        val rows = loadItemPhotosOf(folder)
        val photoResponses = photoViewAssembler.toResponses(rows.map(ItemPhoto::photo))
        val responseById = photoResponses.associateBy { it.photoId }
        return PhotoFolderDetailResponse.of(
            folder = folder,
            photos = photoResponses,
            items = rows.mapNotNull { row ->
                responseById[row.photo.requiredId]?.let { photo ->
                    PhotoFolderItemResponse(photo, row.item.sortOrder, row.item.crop)
                }
            },
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /** 사진을 이미 들고 있는 생성 경로가 다시 읽지 않도록 열어 둔 오버로드. */
    fun detailOf(folder: PhotoFolder, photos: List<Photo>): PhotoFolderDetailResponse =
        photoViewAssembler.toResponses(photos).let { responses ->
            PhotoFolderDetailResponse.of(
                folder = folder,
                photos = responses,
                items = responses.mapIndexed { index, photo -> PhotoFolderItemResponse(photo, index, null) },
                viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            )
        }

    /**
     * 폴더에 담긴 사진을 노출 순서대로 읽는다. 사진이 지워졌다면 그 행은 자연히 빠진다 —
     * 항목이 id만 들고 있어 존재 여부는 읽는 시점에 확인된다.
     */
    private fun loadPhotosOf(folder: PhotoFolder): List<Photo> = loadItemPhotosOf(folder).map(ItemPhoto::photo)

    private fun loadItemPhotosOf(folder: PhotoFolder): List<ItemPhoto> {
        val items = photoFolderItemRepository.findAllByFolderIdOrderBySortOrderAscIdAsc(folder.requiredId)
        val photoIds = items.map { it.photoId }
        if (photoIds.isEmpty()) {
            return emptyList()
        }
        val photos = photoRepository.findAllByGalleryIdAndIdIn(folder.galleryId, photoIds)
            .associateBy { it.requiredId }
        return items.mapNotNull { item -> photos[item.photoId]?.let { ItemPhoto(item, it) } }
            .sortedWith(ITEM_DISPLAY_ORDER)
    }

    private data class ItemPhoto(
        val item: PhotoFolderItem,
        val photo: Photo,
    )

    companion object {
        private val ITEM_DISPLAY_ORDER = compareBy<ItemPhoto> { it.item.sortOrder }
            .thenComparator { left, right -> Photo.DISPLAY_ORDER.compare(left.photo, right.photo) }
    }
}
