package com.soma.wes.studio.support

import com.soma.wes.studio.dto.response.StudioDeletionResponse
import java.util.UUID

sealed interface StudioDeletionPreparation {

    data class Completed(val result: StudioDeletionResponse) : StudioDeletionPreparation

    data class Pending(val plan: StudioDeletionPlan) : StudioDeletionPreparation
}

data class StudioDeletionPlan(
    val requestId: UUID,
    val studioId: Long,
    val studioUserId: Long,
    val studioGalleryUrl: String,
    val galleryIds: Set<Long>,
    val photos: Set<PhotoDeletionTarget>,
) {

    val objectKeys: Set<String>
        get() = photos.flatMapTo(mutableSetOf()) { photo ->
            listOfNotNull(photo.storageKey, photo.expectedPreviewKey, photo.previewKey)
        }
}

data class PhotoDeletionTarget(
    val photoId: Long,
    val storageKey: String,
    val previewKey: String?,
) {

    /**
     * 임베딩 Lambda가 S3에 파생본을 올린 뒤 `preview_key`를 DB에 쓰기 전에도 삭제할 수 있게 한다.
     * Python의 `preview_key_for`와 같은 `previews/{원본 확장자 제거}.jpg` 규칙이다.
     */
    val expectedPreviewKey: String
        get() = "previews/${storageKey.substringBeforeLast('.', storageKey)}.jpg"
}
