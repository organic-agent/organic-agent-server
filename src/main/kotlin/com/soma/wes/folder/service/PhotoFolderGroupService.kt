package com.soma.wes.folder.service

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderGroup
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.dto.request.CreateFolderGroupRequest
import com.soma.wes.folder.dto.request.RenameFolderGroupRequest
import com.soma.wes.folder.dto.response.PhotoFolderGroupResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.folder.support.FolderPhotoLoader
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 자식폴더들을 품는 부모폴더의 생성·조회·이름 변경·삭제.
 */
@Service
class PhotoFolderGroupService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val folderPhotoLoader: FolderPhotoLoader,
    private val folderViewAssembler: FolderViewAssembler,
) {

    /**
     * 부모폴더를 만든다. 클러스터링 결과를 고정할 때는 folders에 묶음별 자식폴더가 함께 오고,
     * 빈 부모만 만들 때는 folders가 비어 있다.
     *
     * 검증을 저장보다 먼저 끝낸다 — 자식 하나가 거절될 요청이 이름뿐인 빈 부모를 남기면 안 된다.
     * 새로 만든 부모는 커밋 전까지 아무도 볼 수 없으므로 부모 행 잠금은 필요 없다.
     */
    @Transactional
    fun create(galleryId: Long, userId: Long, request: CreateFolderGroupRequest): PhotoFolderGroupResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val photosBySeed = loadSeedPhotos(galleryId, request.folders)

        val group = photoFolderGroupRepository.save(PhotoFolderGroup.of(galleryId, request.name))
        request.folders.forEachIndexed { index, seed ->
            val folder = photoFolderRepository.save(PhotoFolder.of(group, seed.name))
            photoFolderItemRepository.saveAll(
                photosBySeed[index].map {
                    PhotoFolderItem(
                        groupId = group.requiredId,
                        folderId = folder.requiredId,
                        photoId = it.requiredId,
                    )
                },
            )
        }

        return responseOf(group)
    }

    /**
     * 자식폴더별 사진을 검증해 요청과 같은 순서로 돌려준다. [create]가 쓴다.
     *
     * 묶음 간 중복을 여기서 잡는다. "같은 부모 아래 사진 중복 금지"는 DB 유니크가 마지막으로
     * 막지만, 그때는 요청 전체가 500으로 실패한다 — 사용자에게는 409로 이유를 말해야 한다.
     */
    private fun loadSeedPhotos(
        galleryId: Long,
        seeds: List<CreateFolderGroupRequest.FolderSeed>,
    ): List<List<Photo>> {
        val allIds = seeds.flatMap { it.photoIds }
        if (allIds.size != allIds.toSet().size) {
            throw FolderException(FolderErrorCode.DUPLICATE_PHOTO_IN_GROUP)
        }
        return seeds.map { folderPhotoLoader.loadPhotosIn(galleryId, it.photoIds) }
    }

    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<PhotoFolderGroupResponse> {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val groups = photoFolderGroupRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId)
        if (groups.isEmpty()) {
            return emptyList()
        }

        val folders = photoFolderRepository.findAllByGroupIdInOrderByIdAsc(groups.map { it.requiredId })
        val summaries = folderViewAssembler.summariesByFolderId(folders)
        val foldersByGroupId = folders.groupBy { it.groupId }

        return groups.map { group ->
            PhotoFolderGroupResponse.of(
                group = group,
                folders = foldersByGroupId[group.requiredId].orEmpty()
                    .mapNotNull { summaries[it.requiredId] },
            )
        }
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, groupId: Long, userId: Long): PhotoFolderGroupResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        return responseOf(findGroup(galleryId, groupId))
    }

    @Transactional
    fun rename(
        galleryId: Long,
        groupId: Long,
        userId: Long,
        request: RenameFolderGroupRequest,
    ): PhotoFolderGroupResponse {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val group = findGroup(galleryId, groupId)
        group.rename(request.name)

        return responseOf(group)
    }

    /**
     * 부모를 지우면 자식폴더와 항목까지 함께 사라진다. DB cascade가 최종 안전망이지만,
     * 이 경로는 지운 수를 확인할 수 있게 명시적으로 지운다.
     */
    @Transactional
    fun delete(galleryId: Long, groupId: Long, userId: Long) {
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val group = findGroup(galleryId, groupId)
        photoFolderItemRepository.deleteAllByGroupId(group.requiredId)
        photoFolderRepository.deleteAllByGroupId(group.requiredId)
        photoFolderGroupRepository.delete(group)
    }

    /** [get]·[rename]·[delete]가 쓴다. */
    private fun findGroup(galleryId: Long, groupId: Long): PhotoFolderGroup =
        photoFolderGroupRepository.findByIdAndGalleryId(groupId, galleryId)
            ?: throw FolderException(FolderErrorCode.GROUP_NOT_FOUND)

    /** [create]·[get]·[rename]이 쓴다. [list]는 여러 부모의 요약을 한 번에 만든다. */
    private fun responseOf(group: PhotoFolderGroup): PhotoFolderGroupResponse {
        val folders = photoFolderRepository.findAllByGroupIdOrderByIdAsc(group.requiredId)
        val summaries = folderViewAssembler.summariesByFolderId(folders)

        return PhotoFolderGroupResponse.of(
            group = group,
            folders = folders.mapNotNull { summaries[it.requiredId] },
        )
    }
}
