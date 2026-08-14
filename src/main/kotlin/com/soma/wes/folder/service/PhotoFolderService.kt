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
 *
 * 부모 안의 사진 구성을 바꾸는 경로(생성·담기·옮기기)는 전부 부모 행을 잠그고 시작한다 —
 * "같은 부모 아래 사진 중복 금지"의 단위가 부모이기 때문이다.
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

        val photos = folderPhotoLoader.loadPhotosIn(galleryId, request.photoIds)
        folderPhotoLoader.requireNoneInGroup(group.requiredId, request.photoIds)
        photoFolderItemRepository.saveAll(
            photos.map {
                PhotoFolderItem(
                    groupId = group.requiredId,
                    folderId = folder.requiredId,
                    photoId = it.requiredId,
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

        val folder = findFolder(galleryId, groupId, folderId)
        folder.rename(request.name)

        return folderViewAssembler.summaryOf(folder)
    }

    @Transactional
    fun delete(galleryId: Long, groupId: Long, folderId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, groupId, folderId)
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

        val photos = folderPhotoLoader.loadPhotosIn(galleryId, request.photoIds)
        folderPhotoLoader.requireNoneInGroup(group.requiredId, request.photoIds)
        photoFolderItemRepository.saveAll(
            photos.map {
                PhotoFolderItem(
                    groupId = group.requiredId,
                    folderId = folder.requiredId,
                    photoId = it.requiredId,
                )
            },
        )

        return folderViewAssembler.detailOf(folder)
    }

    @Transactional
    fun removePhoto(galleryId: Long, groupId: Long, folderId: Long, photoId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val folder = findFolder(galleryId, groupId, folderId)
        // 0이면 없는 사진을 뺀 것이다. 조용히 성공시키면 프론트는 지운 줄 알고 화면에서
        // 지우는데, 실제로는 다른 폴더의 사진이었을 수 있다.
        if (photoFolderItemRepository.deleteByFolderIdAndPhotoId(folder.requiredId, photoId) == 0L) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }
    }

    /**
     * 사진을 같은 부모의 다른 자식폴더로 옮긴다. 옮길 사진이 전부 출발지에 있어야 하고,
     * 도착지는 같은 부모 아래여야 한다 — 다른 부모의 자식 id는 여기서 404가 된다.
     * 응답은 사진이 도착한 폴더의 상세다.
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
        val source = photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
        val target = photoFolderRepository.findByIdAndGroupId(request.targetFolderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)

        // 빈 목록은 옮길 것이 없다는 뜻이 아니라 잘못된 요청이다. @NotEmpty는 컨트롤러를
        // 지날 때만 도는 검증이라 여기서 한 번 더 막는다.
        if (request.photoIds.isEmpty()) {
            throw FolderException(FolderErrorCode.EMPTY_PHOTO_IDS)
        }

        // 출발지와 도착지가 같으면 옮길 것이 없다. 사진이 이미 그 자리에 있으므로 성공이다.
        if (source.requiredId == target.requiredId) {
            return folderViewAssembler.detailOf(target)
        }

        val requested = request.photoIds.toSet()
        val inSource = photoFolderItemRepository.findAllByFolderId(source.requiredId)
            .map { it.photoId }
            .toSet()
        if (!inSource.containsAll(requested)) {
            throw FolderException(FolderErrorCode.PHOTO_NOT_IN_FOLDER)
        }

        photoFolderItemRepository.moveAll(source.requiredId, target.requiredId, requested)

        return folderViewAssembler.detailOf(target)
    }

    /**
     * 부모 안의 사진 구성을 바꾸는 경로가 부모를 잠그고 확인한다.
     * [create]·[addPhotos]·[movePhotos]가 쓴다.
     */
    private fun lockGroup(galleryId: Long, groupId: Long): PhotoFolderGroup =
        photoFolderGroupRepository.findWithLockByIdAndGalleryId(groupId, galleryId)
            ?: throw FolderException(FolderErrorCode.GROUP_NOT_FOUND)

    /**
     * 부모를 (id, galleryId)로, 자식을 (id, groupId)로 이어서 확인한다. 읽기와 사진 구성을
     * 바꾸지 않는 쓰기가 쓴다.
     */
    private fun findFolder(galleryId: Long, groupId: Long, folderId: Long): PhotoFolder {
        val group = photoFolderGroupRepository.findByIdAndGalleryId(groupId, galleryId)
            ?: throw FolderException(FolderErrorCode.GROUP_NOT_FOUND)
        return photoFolderRepository.findByIdAndGroupId(folderId, group.requiredId)
            ?: throw FolderException(FolderErrorCode.FOLDER_NOT_FOUND)
    }
}
