package com.soma.wes.gallery.domain

import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.global.config.TimeConfig
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 상태 전이와 마감 기한 판단만 본다.
 *
 * "지금 사진을 고를 수 있는가"는 상태와 기한을 함께 봐야 나오는 결론이라
 * [com.soma.wes.gallery.service.GalleryAccessPolicy] 쪽에서 검증한다.
 */
class GalleryTest {

    private val now = ZonedDateTime.of(2026, 8, 5, 10, 0, 0, 0, TimeConfig.KST)

    private fun newGallery(
        status: GalleryStatus = GalleryStatus.DRAFT,
        selectionDeadline: ZonedDateTime? = null,
    ) = Gallery(
        studioId = 1L,
        title = "김철수 · 이영희 본식",
        status = status,
        selectionDeadline = selectionDeadline,
    )

    @Test
    fun `준비 중인 갤러리는 멤버에게 보이지 않는다`() {
        val gallery = newGallery(GalleryStatus.DRAFT)

        assertFalse(gallery.isVisibleToMember)
    }

    @Test
    fun `공개하면 멤버가 열람할 수 있다`() {
        val gallery = newGallery(GalleryStatus.DRAFT)

        gallery.open()

        assertEquals(GalleryStatus.OPEN, gallery.status)
        assertTrue(gallery.isVisibleToMember)
    }

    @Test
    fun `마감한 갤러리도 열람은 된다`() {
        val gallery = newGallery(GalleryStatus.OPEN)

        gallery.close()

        assertEquals(GalleryStatus.CLOSED, gallery.status)
        assertTrue(gallery.isVisibleToMember)
    }

    @Test
    fun `마감을 되돌리면 다시 열린다`() {
        val gallery = newGallery(GalleryStatus.CLOSED)

        gallery.reopen(selectionDeadline = now.plusDays(3))

        assertEquals(GalleryStatus.OPEN, gallery.status)
        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `마감을 되돌릴 때 지난 기한을 그대로 두지 않는다`() {
        // 기한을 다시 받지 않으면 열려 있는데 아무도 못 고르는 상태가 조용히 만들어진다.
        val gallery = newGallery(GalleryStatus.CLOSED, selectionDeadline = now.minusDays(1))

        gallery.reopen(selectionDeadline = now.plusDays(3))

        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `이미 공개한 갤러리를 다시 공개할 수 없다`() {
        val gallery = newGallery(GalleryStatus.OPEN)

        val exception = assertFailsWith<GalleryException> { gallery.open() }

        assertEquals(GalleryErrorCode.INVALID_STATUS_TRANSITION, exception.errorCode)
    }

    @Test
    fun `준비 중인 갤러리는 곧바로 마감할 수 없다`() {
        val gallery = newGallery(GalleryStatus.DRAFT)

        val exception = assertFailsWith<GalleryException> { gallery.close() }

        assertEquals(GalleryErrorCode.INVALID_STATUS_TRANSITION, exception.errorCode)
    }

    @Test
    fun `마감 기한을 정하지 않으면 지날 일도 없다`() {
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = null)

        assertFalse(gallery.isDeadlinePassed(now.plusYears(10)))
    }

    @Test
    fun `기한 당일까지는 지나지 않은 것으로 본다`() {
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now)

        assertFalse(gallery.isDeadlinePassed(now))
        assertTrue(gallery.isDeadlinePassed(now.plusNanos(1000)))
    }

    @Test
    fun `기한이 지나도 상태는 OPEN 그대로다`() {
        // 정각에 도는 작업 없이 요청 시점에 판단한다. 작업이 밀려도 마감이 새지 않는다.
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now.minusDays(1))

        assertTrue(gallery.isDeadlinePassed(now))
        assertEquals(GalleryStatus.OPEN, gallery.status)
        assertTrue(gallery.isVisibleToMember)
    }

    @Test
    fun `기한을 연장할 수 있다`() {
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now.minusDays(1))

        gallery.changeSelectionDeadline(now.plusDays(1))

        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `기한을 지우면 무기한이 된다`() {
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now.minusDays(1))

        gallery.changeSelectionDeadline(null)

        assertNull(gallery.selectionDeadline)
        assertFalse(gallery.isDeadlinePassed(now))
    }
}
