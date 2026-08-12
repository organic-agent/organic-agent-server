package com.soma.wes.collab.support

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Component

/**
 * 세션에 담을 수 있는 사진인지 확인하고 돌려준다.
 *
 * 담을 수 있는 것만 담고 나머지를 버리지 않는다. 부부가 고른 묶음에서 몇 장이 조용히 빠지면
 * 화면에는 성공으로 보이고, 어느 사진이 빠졌는지는 아무도 모른다.
 */
@Component
class CollabPhotoLoader(
    private val photoRepository: PhotoRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val properties: StorageProperties,
) {

    /**
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리의 세션으로 남의 사진을 끌어와 하객 링크로 서명 URL까지 내보낼 수 있다.
     */
    fun load(galleryId: Long, photoIds: List<Long>): List<Photo> {
        // 빈 목록을 통과시키면 아무 일도 하지 않고 성공한다. 200을 받은 화면은 담긴 줄 안다.
        if (photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw CollabException(CollabErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw CollabException(CollabErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        // 실체가 없는 사진을 담으면 하객 화면에 깨진 이미지가 뜬다. 작가는 그것이 올라오는
        // 중이라는 뜻임을 알지만 하객은 알 도리가 없다.
        if (photos.any { it.status == PhotoStatus.PENDING }) {
            throw CollabException(CollabErrorCode.PHOTO_NOT_UPLOADED)
        }
        return photos
    }

    /** 폴더가 이 갤러리 것임을 확인해도 그 안의 사진까지 확인한 것은 아니라, [load]를 함께 지난다. */
    fun loadFromFolder(galleryId: Long, folderId: Long): List<Photo> {
        photoFolderRepository.findByIdAndGalleryId(folderId, galleryId)
            ?: throw CollabException(CollabErrorCode.FOLDER_NOT_IN_GALLERY)

        val photoIds = photoFolderItemRepository.findAllByFolderId(folderId).map { it.photoId }
        if (photoIds.isEmpty()) {
            throw CollabException(CollabErrorCode.EMPTY_FOLDER)
        }
        return load(galleryId, photoIds)
    }
}
