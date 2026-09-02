package com.soma.wes.trash

import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.PhotoStorage
import java.time.Instant
import java.util.UUID

/**
 * 호출을 기록하는 [PhotoStorage]. `buildKey`는 실제 구현과 같은 규칙을 쓴다 —
 * 지워진 키가 곧 검증 대상이라 임의 문자열로 대체할 수 없다. [TrashEraserTest]도 같이 쓴다.
 */
class RecordingTrashPhotoStorage : PhotoStorage {

    val deletedBatches = mutableListOf<List<String>>()

    /** true면 [deleteAll]이 실패한다. purge의 "다음 시각에 재시도" 경로를 확인할 때 쓴다. */
    var failDelete = false

    fun reset() {
        deletedBatches.clear()
        failDelete = false
    }

    fun deletedKeys(): Set<String> = deletedBatches.flatten().toSet()

    override fun galleryPrefix(galleryId: Long): String = "galleries/$galleryId/"

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "${galleryPrefix(galleryId)}${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String): PresignedUploadDto =
        PresignedUploadDto(url = "https://storage.test/upload/$key", expiresAt = Instant.now().plusSeconds(1800))

    override fun presignView(key: String): String = "https://storage.test/view/$key"

    override fun presignOriginal(key: String): String = "https://storage.test/original/$key"

    override fun exists(key: String): Boolean = true

    override fun deleteAll(keys: Collection<String>) {
        if (failDelete) {
            throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
        }
        deletedBatches += keys.toList()
    }

    override fun copy(sourceKey: String, targetKey: String) = Unit
}
