package com.soma.wes.selection.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryRepository
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
 *
 * 역할이 경로마다 다르다. 고르고 제출하는 것은 부부의 일이라 [GalleryAccessPolicy.requireCouple]을
 * 지나고(작가가 고객 대신 고를 수 없다), 제출을 되돌리는 것은 작가만 한다 — 부부가 스스로
 * 되돌릴 수 있으면 제출이라는 잠금이 아무것도 잠그지 않는다. 조회는 마감된 뒤에도 양쪽 모두
 * 봐야 하므로 [GalleryAccessPolicy.requireViewer] 기준이다.
 *
 * 고치는 경로는 전부 갤러리 행을 잠그고 시작한다([lockGallery]). 신랑과 신부가 동시에 담는 일이
 * 실제로 일어나는데, 잠그지 않으면 둘 다 "아직 한 장 남았다"를 읽어 계약 장수를 넘기고,
 * 앨범 행이 아직 없을 때는 "없으면 만든다"가 겹쳐 한쪽이 유니크 제약에 걸려 실패한다.
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
     *
     * 아직 한 장도 담지 않았다면 앨범 행이 없다. 그때 행을 만들지 않고 빈 앨범으로 응답한다 —
     * 조회가 쓰기를 하면 갤러리를 열어보기만 한 사람 수만큼 빈 앨범이 쌓인다.
     */
    @Transactional(readOnly = true)
    fun get(galleryId: Long, userId: Long): PhotoSelectionResponse {
        val gallery = galleryAccessPolicy.requireViewer(galleryId, userId)

        return responseOf(gallery, photoSelectionRepository.findByGalleryId(galleryId))
    }

    /**
     * 고른 사진을 담는다.
     *
     * 확인이 저장보다 먼저다. 들어갈 수 있는 만큼만 담고 나머지를 버리면 화면에는 성공으로
     * 보이고, 어느 사진이 빠졌는지는 아무도 모른다.
     */
    @Transactional
    fun select(galleryId: Long, userId: Long, request: SelectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = lockGallery(galleryId)
        val selection = loadOrCreate(galleryId)
        selection.requireEditable()

        val photos = loadSelectablePhotos(galleryId, request.photoIds)
        val alreadySelected = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId)
            .map { it.photoId }
            .toSet()

        // 중복이 먼저다. 겹친 채로 장수를 세면 "몇 장이 넘쳤다"가 실제와 다르고, 사용자는
        // 담기지도 않은 사진 때문에 계약 장수를 넘겼다는 말을 듣는다.
        selection.requireNotSelected(alreadySelected, photos.map { it.requiredId })
        selection.requireWithinTarget(gallery.targetPhotoCount, alreadySelected.size + photos.size)

        photoSelectionItemRepository.saveAll(
            photos.map { PhotoSelectionItem(selectionId = selection.requiredId, photoId = it.requiredId) },
        )

        return responseOf(gallery, selection)
    }

    /**
     * 여러 장을 한 번에 뺀다.
     *
     * 앨범에 없는 id가 섞여 있어도 막지 않는다. 여러 장을 골라 빼는 화면에서 그중 하나가 이미
     * 빠져 있는 것은 사용자의 실수가 아니라 화면이 조금 낡은 것뿐이고, 통째로 거절하면 사용자는
     * 어느 것이 문제인지 모른 채 다시 골라야 한다. 한 장을 지정해 빼는 [deselectPhoto]는 반대다.
     */
    @Transactional
    fun deselect(galleryId: Long, userId: Long, request: DeselectPhotosRequest): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = lockGallery(galleryId)
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
     *
     * 조용히 성공시키면 프론트는 지운 줄 알고 화면에서 지우는데, 실제로는 다른 갤러리의
     * 사진이었을 수 있다.
     */
    @Transactional
    fun deselectPhoto(galleryId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        lockGallery(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        selection.requireEditable()

        if (photoSelectionItemRepository.deleteBySelectionIdAndPhotoId(selection.requiredId, photoId) == 0L) {
            throw SelectionException(SelectionErrorCode.PHOTO_NOT_SELECTED)
        }
    }

    /**
     * 부부가 고르기를 끝내고 작가에게 넘긴다.
     *
     * 계약 장수에 못 미쳐도 받는다 — 이유는 [PhotoSelection.submit]에 적어 두었다.
     * 한 장도 담지 않아 앨범 행조차 없다면 그것은 덜 고른 것이 아니라 고르지 않은 것이다.
     */
    @Transactional
    fun submit(galleryId: Long, userId: Long): PhotoSelectionResponse {
        galleryAccessPolicy.requireCouple(galleryId, userId)

        val gallery = lockGallery(galleryId)
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
     *
     * 부부가 스스로 되돌릴 수 있으면 제출이라는 잠금이 아무것도 잠그지 않는다. 되돌릴지는
     * 이미 보정에 들어갔을 수도 있는 작가가 판단할 일이다.
     */
    @Transactional
    fun withdraw(galleryId: Long, userId: Long): PhotoSelectionResponse {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val gallery = lockGallery(galleryId)
        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: throw SelectionException(SelectionErrorCode.SELECTION_NOT_SUBMITTED)

        selection.withdraw()
        return responseOf(gallery, selection)
    }

    /**
     * 앨범을 고치는 모든 경로가 여기를 지난다. 잠그는 것이 앨범이 아니라 갤러리인 이유는
     * [GalleryRepository.findWithLockById]에 적어 두었다.
     */
    private fun lockGallery(galleryId: Long): Gallery =
        galleryRepository.findWithLockById(galleryId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)

    /** 첫 한 장을 담을 때 앨범이 만들어진다. 갤러리 행이 잠겨 있어 두 요청이 겹치지 않는다. */
    private fun loadOrCreate(galleryId: Long): PhotoSelection =
        photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))

    /**
     * 담을 수 있는 사진인지 확인하고 돌려준다.
     *
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리의 앨범으로 남의 사진을 끌어와
     * 서명 URL까지 받아낼 수 있다.
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
     *
     * [submit]만 사진 목록을 직접 넘긴다. 제출 장수를 세면서 이미 읽어둔 것을 다시 읽지 않는다.
     */
    private fun responseOf(
        gallery: Gallery,
        selection: PhotoSelection?,
        photos: List<Photo> = loadSelectedPhotos(selection),
    ) = PhotoSelectionResponse.of(
        selection = selection,
        targetPhotoCount = gallery.targetPhotoCount,
        photos = photoViewAssembler.toResponses(photos),
        viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
    )

    /**
     * 앨범에 담긴 사진을 노출 순서대로 읽는다.
     *
     * 사진이 지워졌다면 그 행은 자연히 빠진다 — 항목이 id만 들고 있어 존재 여부는 읽는 시점에
     * 확인된다. [com.soma.wes.folder.service.PhotoFolderService]와 같은 방식이다.
     */
    private fun loadSelectedPhotos(selection: PhotoSelection?): List<Photo> {
        if (selection == null) {
            return emptyList()
        }

        val photoIds = photoSelectionItemRepository.findAllBySelectionId(selection.requiredId).map { it.photoId }
        if (photoIds.isEmpty()) {
            return emptyList()
        }

        return photoRepository.findAllByGalleryIdAndIdIn(selection.galleryId, photoIds).sortedWith(PHOTO_ORDER)
    }

    companion object {
        /** 화면 순서는 갤러리에서 정한 노출 순서를 따른다. 같으면 id로 한 번 더 갈라 흔들리지 않게 한다. */
        private val PHOTO_ORDER = compareBy<Photo>({ it.displayOrder }, { it.requiredId })
    }
}
