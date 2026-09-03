package com.soma.wes.folder.service

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderGroup
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.MovePhotosRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.folder.support.FolderPhotoLoader
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 부모폴더 아래 자식폴더의 생성·조회·이름 변경·삭제와 사진 담기·빼기·옮기기.
 */
@Service
class PhotoFolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val folderPhotoLoader: FolderPhotoLoader,
    private val folderViewAssembler: FolderViewAssembler,
) {

    /**
     * 부모 아래 자식폴더를 만든다. photoIds가 비면 빈 폴더다 — 드래그로 채워 넣는 흐름이
     * 빈 폴더에서 시작한다.
     */
    @Transactional
    fun create(
        galleryId: Long,
        groupId: Long,
        userId: Long,
        request: CreatePhotoFolderRequest,
    ): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val group = lockGroup(galleryId, groupId)
        val folder = photoFolderRepository.save(PhotoFolder.of(group, request.name))
        if (request.photoIds.isEmpty()) {
            return folderViewAssembler.detailOf(folder, emptyList())
        }

        val photos = folderPhotoLoader.loadPhotos(galleryId, request.photoIds)
        folderPhotoLoader.validateNoneInGroup(group.requiredId, request.photoIds)
        photoFolderItemRepository.saveAll(
            photos.mapIndexed { index, photo ->
                PhotoFolderItem(
                    groupId = group.requiredId,
                    folderId = folder.requiredId,
                    photoId = photo.requiredId,
                    sortOrder = index,
                )
            },
        )

        return folderViewAssembler.detailOf(folder, photos)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, groupId: Long, folderId: Long, userId: Long): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        return folderViewAssembler.detailOf(findFolder(galleryId, groupId, folderId))
    }

    @Transactional
    fun rename(
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        userId: Long,
        request: RenamePhotoFolderRequest,
    ): PhotoFolderResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolderInLockedGroup(galleryId, groupId, folderId)
        folder.rename(request.name)

        return folderViewAssembler.summaryOf(folder)
    }

    @Transactional
    fun delete(galleryId: Long, groupId: Long, folderId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolderInLockedGroup(galleryId, groupId, folderId)
        // 외래키 cascade가 최종 안전망이지만, 이 경로는 지운 수를 확인할 수 있게 명시적으로 지운다.
        photoFolderItemRepository.deleteAllByFolderId(folder.requiredId)
        photoFolderRepository.delete(folder)
    }

    /**
     * 사진을 담는다. 같은 부모 아래 어딘가에 이미 든 사진이 섞여 있으면 전체를 409로 거절한다.
     */
    @Transactional
    fun addPhotos(
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        userId: Long,
        request: AddPhotosRequest,
    ): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val group = lockGroup(galleryId, groupId)
        val folder = photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)

        val photos = folderPhotoLoader.loadPhotos(galleryId, request.photoIds)
        folderPhotoLoader.validateNoneInGroup(group.requiredId, request.photoIds)
        val firstSortOrder = nextAppendSortOrder(folder.requiredId, photos.size)
        photoFolderItemRepository.saveAll(
            photos.mapIndexed { index, photo ->
                PhotoFolderItem(
                    groupId = group.requiredId,
                    folderId = folder.requiredId,
                    photoId = photo.requiredId,
                    sortOrder = firstSortOrder + index,
                )
            },
        )

        return folderViewAssembler.detailOf(folder)
    }

    @Transactional
    fun removePhoto(galleryId: Long, groupId: Long, folderId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolderInLockedGroup(galleryId, groupId, folderId)

        if (photoFolderItemRepository.deleteByFolderIdAndPhotoId(folder.requiredId, photoId) == 0L) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }
    }

    /** 부모를 (id, galleryId)로, 자식을 (id, groupId)로 이어서 확인하는 읽기 경로다. */
    private fun findFolder(galleryId: Long, groupId: Long, folderId: Long): PhotoFolder {
        val group = photoFolderGroupRepository.findByIdAndGalleryId(groupId, galleryId)
            ?: throw FolderException(FolderErrorCode.GROUP_NOT_FOUND)
        return photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
    }

    /**
     * 관리자 목업 재계산과 같은 부모 행 잠금을 먼저 잡고 자식 구조를 바꾼다. 재계산이 기존
     * 폴더를 읽은 뒤 다시 만드는 사이에 수동 변경이 끼어들어 사라지는 lost update를 막는다.
     */
    private fun findFolderInLockedGroup(galleryId: Long, groupId: Long, folderId: Long): PhotoFolder {
        val group = lockGroup(galleryId, groupId)
        return photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
    }

    /**
     * 사진을 같은 부모의 다른 자식폴더로 옮긴다. 옮길 사진이 전부 출발지에 있어야 하고,
     * 도착지는 같은 부모 아래여야 한다 — 다른 부모의 자식 id는 여기서 404가 된다.
     */
    @Transactional
    fun movePhotos(
        galleryId: Long,
        groupId: Long,
        folderId: Long,
        userId: Long,
        request: MovePhotosRequest,
    ): PhotoFolderDetailResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val group = lockGroup(galleryId, groupId)
        val sourceFolder = photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
        val targetFolder = photoFolderRepository.findByIdAndGroupId(request.targetFolderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)

        if (request.photoIds.isEmpty()) {
            throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)
        }

        // 출발지와 도착지가 같으면 옮길 것이 없다. 사진이 이미 그 자리에 있으므로 성공이다.
        if (sourceFolder.requiredId == targetFolder.requiredId) {
            return folderViewAssembler.detailOf(targetFolder)
        }

        val orderedPhotoIds = request.photoIds.distinct()
        val requestPhotoIds = orderedPhotoIds.toSet()
        val photoIdsInSourceFolder = photoFolderItemRepository.findAllByFolderId(sourceFolder.requiredId)
            .map { it.photoId }
            .toSet()
        if (!photoIdsInSourceFolder.containsAll(requestPhotoIds)) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }

        val firstSortOrder = nextAppendSortOrder(targetFolder.requiredId, orderedPhotoIds.size)
        orderedPhotoIds.forEachIndexed { index, photoId ->
            if (photoFolderItemRepository.moveOne(
                    sourceFolder.requiredId,
                    targetFolder.requiredId,
                    photoId,
                    firstSortOrder + index,
                ) != 1
            ) {
                throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
            }
        }

        return folderViewAssembler.detailOf(targetFolder)
    }

    /**
     * 새 항목이 기존 표시 순서 뒤에 오도록 시작값을 구한다. 관리자 배치가 Int 상한을 쓴
     * 극단적인 경우에만 현재 표시 순서를 0부터 재번호화해 overflow를 피한다.
     */
    private fun nextAppendSortOrder(folderId: Long, additionalCount: Int): Int {
        val items = photoFolderItemRepository.findAllByFolderIdOrderBySortOrderAscIdAsc(folderId)
        val maxSortOrder = items.maxOfOrNull(PhotoFolderItem::sortOrder) ?: return 0
        if (maxSortOrder <= Int.MAX_VALUE - additionalCount) return maxSortOrder + 1

        items.forEachIndexed { index, item ->
            photoFolderItemRepository.updateSortOrder(
                item.id ?: error("saved photo folder item has no id"),
                index,
            )
        }
        return items.size
    }

    /**
     * 부모 안의 사진 구성을 바꾸는 경로가 부모를 잠그고 확인한다.
     * [create]·[rename]·[delete]·[addPhotos]·[removePhoto]·[movePhotos]가 쓴다.
     */
    private fun lockGroup(galleryId: Long, groupId: Long): PhotoFolderGroup =
        photoFolderGroupRepository.findWithLockByIdAndGalleryId(groupId, galleryId)
            ?: throw FolderException(FolderErrorCode.GROUP_NOT_FOUND)
}
