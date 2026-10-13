package com.soma.wes.trash

import com.soma.wes.photo.dto.PresignedUploadDto
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.port.PhotoStorage
import java.time.Instant
import java.util.UUID

/**
 * 호출을 기록하는 [PhotoStorage]. `buildKey`는 실제 구현과 같은 규칙을 쓴다 —
 * 지워진 키가 곧 검증 대상이라 임의 문자열로 대체할 수 없다. [TrashEraserTest]·`PendingUploadSweeperTest`도 같이 쓴다.
 */
class RecordingTrashPhotoStorage : PhotoStorage {

    val deletedBatches = mutableListOf<List<String>>()

    /** [exists]가 물어본 키. PENDING 보정 스윕이 HeadObject 를 몇 번 부르는지 볼 때 쓴다. */
    val existsCalls = mutableListOf<String>()

    /** true면 [deleteAll]이 실패한다. purge의 "다음 시각에 재시도" 경로를 확인할 때 쓴다. */
    var failDelete = false

    /** [exists]의 답. 기본은 "전부 있다". */
    var existsAnswer: (String) -> Boolean = { true }

    fun reset() {
        deletedBatches.clear()
        existsCalls.clear()
        failDelete = false
        existsAnswer = { true }
    }

    fun deletedKeys(): Set<String> = deletedBatches.flatten().toSet()

    override fun galleryPrefix(galleryId: Long): String = "galleries/$galleryId/"

    override fun buildKey(galleryId: Long, originalFileName: String): String {
        val extension = originalFileName.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.isBlank()) "" else ".$extension"
        return "${galleryPrefix(galleryId)}${UUID.randomUUID()}$suffix"
    }

    override fun presignUpload(key: String, contentType: String, contentLength: Long?, crc32c: String?): PresignedUploadDto =
        PresignedUploadDto(url = "https://storage.test/upload/$key", expiresAt = Instant.now().plusSeconds(1800))

    override fun presignView(key: String): String = "https://storage.test/view/$key"

    override fun presignOriginal(key: String): String = "https://storage.test/original/$key"

    override fun exists(key: String): Boolean {
        existsCalls += key
        return existsAnswer(key)
    }

    override fun deleteAll(keys: Collection<String>) {
        if (failDelete) {
            throw PhotoException(PhotoErrorCode.STORAGE_DELETE_FAILED)
        }
        deletedBatches += keys.toList()
    }

    override fun copy(sourceKey: String, targetKey: String) = Unit
}
