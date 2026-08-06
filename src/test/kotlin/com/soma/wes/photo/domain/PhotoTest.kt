package com.soma.wes.photo.domain

import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PhotoTest {

    private fun photo() = Photo(
        galleryId = 1L,
        storageKey = "galleries/1/a.jpg",
        originalFileName = "a.jpg",
        contentType = "image/jpeg",
    )

    @Test
    fun `발급 직후에는 PENDING이다`() {
        // 서명 URL만 나갔을 뿐 S3에는 아직 객체가 없다. 이 상태의 사진에 조회 URL을 주면
        // 프론트가 깨진 이미지를 그린다.
        val photo = photo()

        assertEquals(PhotoStatus.PENDING, photo.status)
        assertNull(photo.embedding)
    }

    @Test
    fun `완료 통보를 받으면 UPLOADED가 된다`() {
        val photo = photo()

        photo.markUploaded()

        assertEquals(PhotoStatus.UPLOADED, photo.status)
    }

    @Test
    fun `이미 임베딩까지 끝난 사진은 완료 통보로 되돌아가지 않는다`() {
        // 완료 통보가 뒤늦게 도착해 방금 채운 EMBEDDED를 UPLOADED로 되돌리면,
        // 벡터는 멀쩡한데 집계만 틀리는 상태가 된다. 증상이 원인을 가리키지 않는다.
        val photo = photo()
        photo.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION))

        photo.markUploaded()

        assertEquals(PhotoStatus.EMBEDDED, photo.status)
    }

    @Test
    fun `임베딩을 적재하면 EMBEDDED가 된다`() {
        val photo = photo()
        val vector = FloatArray(Photo.EMBEDDING_DIMENSION) { 0.1f }

        photo.applyEmbedding(vector)

        assertEquals(PhotoStatus.EMBEDDED, photo.status)
        assertEquals(Photo.EMBEDDING_DIMENSION, photo.embedding?.size)
    }

    @Test
    fun `차원이 다른 벡터는 적재하지 않는다`() {
        // vector(n) 컬럼이 결국 거절하지만, 그때는 이미 배치 하나를 통째로 계산한 뒤다.
        val photo = photo()

        val exception = assertFailsWith<PhotoException> {
            photo.applyEmbedding(FloatArray(512))
        }

        assertEquals(PhotoErrorCode.EMBEDDING_DIMENSION_MISMATCH, exception.errorCode)
        assertEquals(PhotoStatus.PENDING, photo.status)
        assertNull(photo.embedding)
    }
}
