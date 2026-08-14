package com.soma.wes.selection.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.domain.PhotoSelectionItem
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.dto.response.PhotoSelectionResponse
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 예비 부부가 최종적으로 고른 사진을 담는 선택 앨범.
 */
@Service
class PhotoSelectionService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val clock: Clock,
) {

    /**
     * 앨범을 연다. 마감 뒤에도, 제출 뒤에도 보인다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): PhotoSelectionResponse {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)

        return responseOf(gallery, photoSelectionRepository.findByGalleryId(galleryId))
    }

    /**
     * 고른 사진을 담는다.
     */
    @Transactional
    fun select(galleryId: Long, userId: Long, request: SelectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = loadOrCreate(galleryId)
        selection.requireEditable()

        val photos = loadSelectablePhotos(galleryId, request.photoIds)
        val alreadySelected = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId)
            .map { it.photoId }
            .toSet()

        // 중복이 먼저다. 겹친 채로 장수를 세면 "몇 장이 넘쳤다"가 실제와 다르고, 사용자는
        // 담기지도 않은 사진 때문에 계약 장수를 넘겼다는 말을 듣는다.
        selection.requireNotSelected(alreadySelected, photos.map { it.requiredId })
        selection.requireWithinMax(gallery.maxSelectablePhotoCount, alreadySelected.size + photos.size)

        photoSelectionItemRepository.saveAll(
            photos.map { PhotoSelectionItem(selectionId = selection.requiredId, photoId = it.requiredId) },
        )

        return responseOf(gallery, selection)
    }

    /**
     * 여러 장을 한 번에 뺀다.
     */
    @Transactional
    fun deselect(galleryId: Long, userId: Long, request: DeselectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: return responseOf(gallery, null)
        selection.requireEditable()

        photoSelectionItemRepository.deleteAllBySelectionIdAndPhotoIdIn(
            selection.requiredId,
            request.photoIds.toSet(),
        )

        return responseOf(gallery, selection)
    }

    /**
     * 한 장을 빼낸다. 앨범에 없으면 404다.
     */
    @Transactional
    fun deselectPhoto(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        galleryRepository.requireWithLockById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        selection.requireEditable()

        if (photoSelectionItemRepository.deleteBySelectionIdAndPhotoId(selection.requiredId, photoId) == 0L) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        }
    }

    /**
     * 부부가 고르기를 끝내고 작가에게 넘긴다.
     */
    @Transactional
    fun submit(galleryId: Long, userId: Long): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.EMPTY_SELECTION)

        // 항목 행이 아니라 실제로 남아 있는 사진을 센다. 응답에 실리는 목록과 같은 기준이어야
        // "몇 장을 제출했는지"를 두 값이 다르게 말하지 않는다.
        val photos = loadSelectedPhotos(selection)
        selection.submit(photos.size, ZonedDateTime.now(clock))

        return responseOf(gallery, selection, photos)
    }

    /**
     * 제출을 되돌려 부부가 다시 고를 수 있게 한다. 담당 작가만 할 수 있다.
     */
    @Transactional
    fun withdraw(galleryId: Long, userId: Long): PhotoSelectionResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val gallery = galleryRepository.requireWithLockById(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.SELECTION_NOT_SUBMITTED)

        selection.withdraw()
        return responseOf(gallery, selection)
    }

    /** 첫 한 장을 담을 때 앨범이 만들어진다. 갤러리 행이 잠겨 있어 두 요청이 겹치지 않는다. */
    private fun loadOrCreate(galleryId: Long): PhotoSelection =
        photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))

    /**
     * 담을 수 있는 사진인지 확인하고 돌려준다.
     */
    private fun loadSelectablePhotos(galleryId: Long, photoIds: List<Long>): List<Photo> {
        // 빈 목록을 통과시키면 아무 일도 하지 않고 성공한다. 200을 받은 화면은 담긴 줄 안다.
        if (photoIds.isEmpty()) {
            throw SelectionException(SelectionErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw SelectionException(SelectionErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        // 실체가 없는 사진이 납품 목록에 섞이면, 작가는 목록에는 있는데 열리지 않는 항목을 받는다.
        if (photos.any { it.status == PhotoStatus.PENDING }) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_UPLOADED)
        }
        return photos
    }

    /**
     * `selection`이 null이면 아직 앨범 행이 없는 갤러리다 — 빈 앨범으로 응답한다.
     */
    private fun responseOf(
        gallery: Gallery,
        selection: PhotoSelection?,
        photos: List<Photo> = loadSelectedPhotos(selection),
    ) = PhotoSelectionResponse.of(
        selection = selection,
        maxSelectablePhotoCount = gallery.maxSelectablePhotoCount,
        photos = photoViewAssembler.toResponses(photos),
        viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
    )

    /**
     * 앨범에 담긴 사진을 노출 순서대로 읽는다.
     */
    private fun loadSelectedPhotos(selection: PhotoSelection?): List<Photo> {
        if (selection == null) {
            return emptyList()
        }

        val photoIds = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId).map { it.photoId }
        if (photoIds.isEmpty()) {
            return emptyList()
        }

        return photoRepository.findAllByGalleryIdAndIdIn(selection.galleryId, photoIds).sortedWith(Photo.DISPLAY_ORDER)
    }
}
