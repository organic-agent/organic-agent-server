package com.soma.wes.folder.service

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 예비 부부가 확정한 사진 묶음.
 *
 * 클러스터는 임계값을 바꿀 때마다 다른 모양이 되지만 폴더는 그러면 안 된다. 그래서 만드는
 * 시점의 사진 목록을 [PhotoFolderItem] 행으로 고정한다 — 클러스터를 가리키는 포인터가 아니라
 * 사용자가 "이걸로 하겠다"고 확정한 목록이다.
 *
 * 모든 경로가 [GalleryAccessPolicy.requirePhotographerOrCouple]를 지난다. 고르는 것은 부부의
 * 일이지만 작가도 자기 갤러리의 폴더를 만지고 확인할 수 있어야 한다 — 다만 부부에게만 갤러리가
 * 열려 있고 마감 전이어야 한다는 조건이 붙는다.
 */
@Service
class PhotoFolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
) {

    @Transactional
    fun create(galleryId: Long, userId: Long, request: CreatePhotoFolderRequest): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val photos = loadPhotosIn(galleryId, request.photoIds)
        val folder = photoFolderRepository.save(newFolder(galleryId, request.name))

        photoFolderItemRepository.saveAll(
            photos.map { PhotoFolderItem(folderId = folder.requiredId, photoId = it.requiredId) },
        )

        return detailOf(folder, photos)
    }

    /**
     * 이름 규칙 위반을 도메인 예외로 옮긴다.
     *
     * [PhotoFolder.normalizeName]이 던지는 것은 `IllegalArgumentException`이라 그대로 두면 500이
     * 나간다. 컨트롤러의 `@Valid`가 대부분 먼저 걸러내지만, 그 검증은 컨트롤러를 지날 때만 돈다.
     */
    private fun newFolder(galleryId: Long, name: String): PhotoFolder =
        try {
            PhotoFolder.of(galleryId, name)
        } catch (e: IllegalArgumentException) {
            throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
        }

    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<PhotoFolderResponse> {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folders = photoFolderRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId)
        if (folders.isEmpty()) {
            return emptyList()
        }

        // 폴더마다 질의를 돌리면 목록 길이만큼 늘어난다. 항목과 사진을 한 번씩만 읽어 나눈다.
        val items = photoFolderItemRepository.findAllByFolderIdIn(folders.map { it.requiredId })

        // 항목 행이 아니라 실제로 남아 있는 사진을 본다. 항목만 세면 사진이 지워진 뒤
        // 목록의 photoCount가 상세 조회의 photos.size보다 커진다 -- 같은 폴더를 두 화면이
        // 다르게 말하게 된다. 대표 사진도 같은 이유로 살아 있는 것 중에서 고른다.
        val alive = existingPhotos(galleryId, items.map { it.photoId })
        val photosByFolderId = items.mapNotNull { item -> alive[item.photoId]?.let { item.folderId to it } }
            .groupBy({ it.first }, { it.second })

        return folders.map { folder ->
            // 상세 조회와 같은 정렬이라 카드의 대표 사진과 팝업의 첫 장이 어긋나지 않는다.
            val photos = photosByFolderId[folder.requiredId].orEmpty().sortedWith(PHOTO_ORDER)
            summaryOf(folder, photos)
        }
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, folderId: Long, userId: Long): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, folderId)
        return detailOf(folder, loadPhotosOf(folder))
    }

    @Transactional
    fun rename(
        galleryId: Long,
        folderId: Long,
        userId: Long,
        request: RenamePhotoFolderRequest,
    ): PhotoFolderResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, folderId)
        try {
            folder.rename(request.name)
        } catch (e: IllegalArgumentException) {
            throw FolderException(FolderErrorCode.INVALID_FOLDER_NAME)
        }

        return summaryOf(folder, loadPhotosOf(folder))
    }

    @Transactional
    fun delete(galleryId: Long, folderId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, folderId)
        // 외래키를 걸지 않았으므로 항목을 먼저 지운다. 남겨 두면 어느 폴더에도 속하지 않은
        // 행이 쌓이고, 나중에 같은 id가 재사용되면 엉뚱한 폴더에 사진이 나타난다.
        photoFolderItemRepository.deleteAllByFolderId(folder.requiredId)
        photoFolderRepository.delete(folder)
    }

    @Transactional
    fun addPhotos(
        galleryId: Long,
        folderId: Long,
        userId: Long,
        request: AddPhotosRequest,
    ): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        // 폴더 행을 잠그고 시작한다. "이미 든 것 읽기 -> 없는 것만 저장" 사이에 같은 폴더로
        // 다른 요청이 끼어들면 둘 다 없다고 판단해 같은 행을 저장하고, 유니크 제약에 걸린
        // 한쪽이 통째로 실패한다.
        val folder = photoFolderRepository.findWithLockByIdAndGalleryId(folderId, galleryId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
        val photos = loadPhotosIn(galleryId, request.photoIds)

        // 이미 들어 있는 사진은 건너뛴다. 사용자가 보기에는 "몇 장은 이미 있다"일 뿐인
        // 상황이라 요청 전체를 실패시킬 이유가 없다.
        val existing = photoFolderItemRepository.findAllByFolderId(folder.requiredId).map { it.photoId }.toSet()
        photoFolderItemRepository.saveAll(
            photos.filter { it.requiredId !in existing }
                .map { PhotoFolderItem(folderId = folder.requiredId, photoId = it.requiredId) },
        )

        return detailOf(folder, loadPhotosOf(folder))
    }

    /**
     * 요청에 다른 갤러리의 사진 id가 섞여 있는지 확인하고, 노출 순서대로 돌려준다.
     *
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리에 만든 폴더로 남의 사진을 끌어와
     * 서명 URL까지 받아낼 수 있다.
     *
     * [create]와 [addPhotos]가 쓴다.
     */
    private fun loadPhotosIn(galleryId: Long, photoIds: List<Long>): List<Photo> {
        // 빈 목록을 통과시키면 사진 없는 폴더가 만들어진다. DTO의 @NotEmpty는 컨트롤러를
        // 지날 때만 도는 검증이라 여기서 한 번 더 막는다.
        if (photoIds.isEmpty()) {
            throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)
        }
        if (photoIds.size > properties.maxBatchSize) {
            throw FolderException(FolderErrorCode.TOO_MANY_PHOTOS)
        }

        val requested = photoIds.toSet()
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, requested)
        if (photos.size != requested.size) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_GALLERY)
        }
        return photos.sortedWith(PHOTO_ORDER)
    }

    /** [create]·[get]·[addPhotos]가 쓴다. */
    private fun detailOf(folder: PhotoFolder, photos: List<Photo>) = PhotoFolderDetailResponse.of(
        folder = folder,
        photos = photoViewAssembler.toResponses(photos),
        viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
    )

    /**
     * 폴더에 담긴 사진을 노출 순서대로 읽는다. [get]과 [addPhotos]가 쓴다.
     *
     * 사진이 지워졌다면 그 행은 자연히 빠진다 -- 폴더 항목이 id만 들고 있어 존재 여부는
     * 읽는 시점에 확인된다.
     */
    private fun loadPhotosOf(folder: PhotoFolder): List<Photo> {
        val photoIds = photoFolderItemRepository.findAllByFolderId(folder.requiredId).map { it.photoId }
        if (photoIds.isEmpty()) {
            return emptyList()
        }

        return photoRepository.findAllByGalleryIdAndIdIn(folder.galleryId, photoIds).sortedWith(PHOTO_ORDER)
    }

    @Transactional
    fun removePhoto(galleryId: Long, folderId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, folderId)
        // 0이면 없는 사진을 뺀 것이다. 조용히 성공시키면 프론트는 지운 줄 알고 화면에서
        // 지우는데, 실제로는 다른 폴더의 사진이었을 수 있다.
        if (photoFolderItemRepository.deleteByFolderIdAndPhotoId(folder.requiredId, photoId) == 0L) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }
    }

    /** [get]·[rename]·[delete]·[removePhoto]가 쓴다. [addPhotos]만 잠금이 필요해 따로 읽는다. */
    private fun findFolder(galleryId: Long, folderId: Long): PhotoFolder =
        photoFolderRepository.findByIdAndGalleryId(folderId, galleryId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)

    /**
     * 아직 남아 있는 사진을 id로 찾을 수 있게 모아 돌려준다.
     *
     * [list]가 지워진 사진을 세지 않고 대표 사진도 살아 있는 것에서 고르려고 쓴다.
     * 폴더 수와 무관하게 질의는 하나다.
     */
    private fun existingPhotos(galleryId: Long, photoIds: List<Long>): Map<Long, Photo> {
        if (photoIds.isEmpty()) {
            return emptyMap()
        }
        return photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds.toSet())
            .associateBy { it.requiredId }
    }

    /** 목록용 응답. 사진 전부 대신 대표 한 장만 담는다. [list]와 [rename]이 쓴다. */
    private fun summaryOf(folder: PhotoFolder, photos: List<Photo>) = PhotoFolderResponse.of(
        folder = folder,
        photoCount = photos.size.toLong(),
        coverPhoto = photos.firstOrNull()?.let(photoViewAssembler::toResponse),
    )

    companion object {
        /** 화면 순서는 갤러리에서 정한 노출 순서를 따른다. 같으면 id로 한 번 더 갈라 흔들리지 않게 한다. */
        private val PHOTO_ORDER = compareBy<Photo>({ it.displayOrder }, { it.requiredId })
    }
}
