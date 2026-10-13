package com.soma.wes.admin.support

import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.repository.AdminResourceRepository
import com.soma.wes.gallery.support.GalleryPhotoQuota
import org.springframework.stereotype.Component

@Component
class AdminPhotoRestoreQuota(
    private val resources: AdminResourceRepository,
    private val galleryPhotoQuota: GalleryPhotoQuota,
) {
    /** 관리자 사진 복원도 새 업로드와 같은 한도를 지켜 삭제·재업로드 후 복원으로 한도를 넘지 못한다. */
    fun requireCapacity(type: AdminResourceType, id: Long) {
        if (type != AdminResourceType.PHOTO) return
        val photo = resources.find(type, id) ?: throw AdminException(AdminErrorCode.RESOURCE_NOT_FOUND)
        val galleryId = checkNotNull((photo.fields["galleryId"] as? Number)?.toLong()) { "사진의 갤러리 id가 없다." }
        galleryPhotoQuota.requireCapacity(galleryId = galleryId, additionalPhotoCount = RESTORED_PHOTO_COUNT)
    }

    companion object {
        /** 사진 하나를 루트로 복원하는 관리자 경로의 사진 증가량. */
        private const val RESTORED_PHOTO_COUNT = 1
    }
}
