package com.soma.wes.gallery

import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.port.PhotoStorage
import java.time.Clock
import java.util.UUID

/**
 * 호출을 기록하는 [PhotoStorage]. `buildKey`·`previewKeyOf`는 실제 구현과 같은 규칙을 쓴다 —
 * 키 모양(`galleries/{id}/{uuid}.{ext}`)이 곧 검증 대상이라 임의 문자열로 대체할 수 없다.
 */
class RecordingPhotoStorage(
    private val clock: Clock,
) : PhotoStorage {

    val copies = mutableListOf<Pair<String, String>>()
    val deleted = mutableListOf<String>()

    /** 이만큼 복사한 뒤부터는 실패를 주입한다. null이면 항상 성공. */
    var failCopyAfter: Int? = null

    fun reset() {
        copies.clear()
        deleted.clear()
        failCopyAfter = null
    }

    override fun galleryPrefix(galleryId: Long): String = "galleries/$galleryId/"

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "${galleryPrefix(galleryId)}${UUID.randomUUID()}$suffix"
    }

    override fun previewKeyOf(storageKey: String): String =
        "previews/${storageKey.substringBeforeLast('.', storageKey)}.jpg"

    override fun presignUpload(key: String, contentType: String, contentLength: Long?, crc32c: String?): PresignedUploadDto =
        PresignedUploadDto(url = "https://storage.test/upload/$key", expiresAt = clock.instant().plusSeconds(1800))

    override fun presignView(key: String): String = "https://storage.test/view/$key"

    override fun presignOriginal(key: String): String = "https://storage.test/original/$key"

    override fun exists(key: String): Boolean = true

    override fun deleteAll(keys: Collection<String>) {
        deleted += keys
    }

    override fun copy(sourceKey: String, targetKey: String) {
        failCopyAfter?.let { limit ->
            if (copies.size >= limit) {
                throw PhotoException(PhotoErrorCode.STORAGE_COPY_FAILED)
            }
        }
        copies += sourceKey to targetKey
    }
}
