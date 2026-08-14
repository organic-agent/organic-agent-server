package com.soma.wes.photo.domain

import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `파생본이 없으면 원본을 보여준다`() {
        // 파생본은 임베딩 Lambda가 만든다. 그전까지는 원본밖에 없고, JPEG·PNG라면
        // 브라우저가 그대로 그린다 -- 아무것도 못 보여주는 것보다 낫다.
        val photo = photo()

        assertNull(photo.previewKey)
        assertEquals(photo.storageKey, photo.viewKey)
    }

    @Test
    fun `파생본이 있으면 원본 대신 그쪽을 보여준다`() {
        // 원본이 HEIC면 브라우저가 그리지 못하므로 이 대체가 미리보기의 유일한 통로다.
        val photo = photo()

        photo.previewKey = "previews/galleries/1/a.jpg"

        assertEquals("previews/galleries/1/a.jpg", photo.viewKey)
        // 원본 위치는 그대로다. 파생본은 화면용일 뿐 원본을 대신하지 않는다.
        assertEquals("galleries/1/a.jpg", photo.storageKey)
    }

    @Test
    fun `휴지통으로 보내면 시각이 남는다`() {
        // 이 시각이 곧 보관 만료의 기준이다. @SQLRestriction이 이 값으로 모든 조회에서 걸러낸다.
        val photo = photo()
        val at = ZonedDateTime.now()

        photo.moveToTrash(at)

        assertEquals(at, photo.deletedAt)
    }

    @Test
    fun `촬영 정보는 임베딩 Lambda가 채우기 전까지 비어 있다`() {
        // 이 서버는 이미지 바이트를 만지지 않아 EXIF를 읽을 방법이 없다. 발급 시점에
        // 짐작해 채워두면 값이 있다는 것과 실제 촬영 정보가 어긋난다.
        assertNull(photo().metadata)
    }

    @Test
    fun `촬영 정보를 적재한다`() {
        val photo = photo()

        photo.applyMetadata(PhotoMetadata(cameraMake = "Apple", width = 4032, height = 3024))

        assertEquals("Apple", photo.metadata?.cameraMake)
        assertEquals(4032, photo.metadata?.width)
        // EXIF가 없는 파일도 있어서 나머지는 비어 있는 것이 정상이다.
        assertNull(photo.metadata?.takenAt)
    }

    @Test
    fun `값이 하나도 없는 촬영 정보는 비어 있다고 말한다`() {
        // 응답을 만드는 쪽이 이 값을 보고 metadata를 통째로 null로 내린다. 빈 객체를
        // 내려주면 화면이 "촬영 정보" 칸을 열어놓고 빈 줄만 늘어놓는다.
        assertTrue(PhotoMetadata().isEmpty)
        assertFalse(PhotoMetadata(byteSize = 1_024).isEmpty)
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

    @Test
    fun `임베딩까지 끝난 사진을 다른 갤러리로 복제한다`() {
        val source = photo()
        source.previewKey = "previews/galleries/1/a.jpg"
        source.applyMetadata(PhotoMetadata(cameraMake = "Apple", width = 4032, height = 3024))
        source.applyEmbedding(FloatArray(Photo.EMBEDDING_DIMENSION) { 0.1f })

        val copy = Photo.copyOf(
            source = source,
            galleryId = 2L,
            storageKey = "galleries/2/b.jpg",
            previewKey = "previews/galleries/2/b.jpg",
            displayOrder = 7,
        )

        assertEquals(2L, copy.galleryId)
        assertEquals("galleries/2/b.jpg", copy.storageKey)
        assertEquals("previews/galleries/2/b.jpg", copy.previewKey)
        assertEquals(7, copy.displayOrder)
        assertEquals("a.jpg", copy.originalFileName)
        assertEquals("image/jpeg", copy.contentType)
        assertEquals(PhotoStatus.EMBEDDED, copy.status)
        // 업로드 URL을 발급한 적 없는 행이다. 값이 있으면 휴지통 즉시 삭제가 30분간 막힌다.
        assertNull(copy.uploadUrlExpiresAt)
        // 값은 같되 인스턴스는 나눠 갖지 않는다. detached 원본과 상태가 엮이면 안 된다.
        assertContentEquals(source.embedding, copy.embedding)
        assertNotSame(source.embedding, copy.embedding)
        assertEquals("Apple", copy.metadata?.cameraMake)
        assertNotSame(source.metadata, copy.metadata)
    }

    @Test
    fun `임베딩이 없는 사진은 복제할 수 없다`() {
        // 벡터 없는 복사본은 임베딩 실행 대상이 되어 Lambda가 S3 원본을 다시 읽게 된다.
        // "즉시 체험"이라는 목적과 어긋나므로 템플릿 쪽 시드를 끝내고 오라는 뜻이다.
        val source = photo()

        assertFailsWith<IllegalStateException> {
            Photo.copyOf(source, galleryId = 2L, storageKey = "galleries/2/b.jpg", previewKey = null, displayOrder = 0)
        }
    }
}
