package com.soma.wes.folder.support

import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.stereotype.Component

/**
 * 폴더에 담을 사진 요청을 검증한다. 부모·자식 서비스의 모든 담기 경로가 지나는 곳이다.
 */
@Component
class FolderPhotoLoader(
    private val photoRepository: PhotoRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val properties: StorageProperties,
) {

    /**
     * 요청에 다른 갤러리의 사진 id가 섞여 있는지 확인하고, 노출 순서대로 돌려준다.
     *
     * 갤러리 권한만 보고 사진 id를 그대로 믿으면, 자기 갤러리에 만든 폴더로 남의 사진을
     * 끌어와 서명 URL까지 받아낼 수 있다.
     */
    fun loadPhotosIn(galleryId: Long, photoIds: Collection<Long>): List<Photo> {
        // 빈 목록은 담을 것이 없다는 뜻이 아니라 잘못된 요청이다. DTO의 @NotEmpty는
        // 컨트롤러를 지날 때만 도는 검증이라 여기서 한 번 더 막는다.
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
        return photos.sortedWith(FolderViewAssembler.PHOTO_ORDER)
    }

    /**
     * 부모 스코프 중복을 걸러낸다. 호출자는 부모 행을 잠근 뒤에 불러야 한다 — 잠그지 않으면
     * 두 요청이 모두 이 검사를 통과해 유니크 제약에 걸린 한쪽이 500으로 실패한다.
     */
    fun requireNoneInGroup(groupId: Long, photoIds: Collection<Long>) {
        if (photoFolderItemRepository.findAllByGroupIdAndPhotoIdIn(groupId, photoIds).isNotEmpty()) {
            throw FolderException(FolderErrorCode.DUPLICATE_PHOTO_IN_GROUP)
        }
    }
}
