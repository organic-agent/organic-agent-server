package com.soma.wes.support

import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import com.soma.wes.photo.service.port.PreviewImageReader

/**
 * S3 없이 미리보기를 읽는 척한다. 기본은 키를 그대로 바이트로 돌려주므로 테스트가 어떤 사진이 갔는지 확인할 수 있다.
 *
 * 픽셀을 실제로 다루는 테스트(보정 요청의 마커·크롭)는 [put]으로 진짜 JPEG를 심고, [fail]로 읽기 실패를 흉내 낸다.
 * DB 밖 상태라 [DatabaseCleaner]가 모르므로 테스트가 `@BeforeEach`에서 [reset]한다.
 */
class FakePreviewImageReader : PreviewImageReader {

    private val images = mutableMapOf<String, ByteArray>()
    private val failures = mutableSetOf<String>()

    fun put(previewKey: String, jpeg: ByteArray) {
        images[previewKey] = jpeg
    }

    fun fail(previewKey: String) {
        failures += previewKey
    }

    fun reset() {
        images.clear()
        failures.clear()
    }

    override fun readJpeg(previewKey: String, longEdge: Int): ByteArray = read(previewKey)

    override fun read(previewKey: String): ByteArray {
        if (previewKey in failures) throw PhotoException(PhotoErrorCode.STORAGE_READ_FAILED)
        return images[previewKey] ?: previewKey.toByteArray()
    }
}
