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
 * [com.soma.wes.gallery.support.GalleryAccessPolicy] 쪽에서 검증한다.
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

        gallery.reopen(selectionDeadline = now.plusDays(3), at = now)

        assertEquals(GalleryStatus.OPEN, gallery.status)
        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `마감을 되돌릴 때 지난 기한을 그대로 두지 않는다`() {
        // 기한을 다시 받지 않으면 열려 있는데 아무도 못 고르는 상태가 조용히 만들어진다.
        val gallery = newGallery(GalleryStatus.CLOSED, selectionDeadline = now.minusDays(1))

        gallery.reopen(selectionDeadline = now.plusDays(3), at = now)

        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `이미 지난 기한으로는 다시 열 수 없다`() {
        // 통과시키면 열려 있는데 아무도 못 고르는 갤러리가 만들어지고, 부부는 이유를 알 수 없는
        // 403만 본다. 작가가 실수를 알아챌 수 있는 지점은 값을 넣는 지금뿐이다.
        val gallery = newGallery(GalleryStatus.CLOSED)

        val exception = assertFailsWith<GalleryException> {
            gallery.reopen(selectionDeadline = now.minusDays(1), at = now)
        }

        assertEquals(GalleryErrorCode.INVALID_SELECTION_DEADLINE, exception.errorCode)
        assertEquals(GalleryStatus.CLOSED, gallery.status)
    }

    @Test
    fun `기한 없이도 다시 열 수 있다`() {
        val gallery = newGallery(GalleryStatus.CLOSED, selectionDeadline = now.minusDays(1))

        gallery.reopen(selectionDeadline = null, at = now)

        assertEquals(GalleryStatus.OPEN, gallery.status)
        assertNull(gallery.selectionDeadline)
    }

    @Test
    fun `상태를 먼저 보고 기한을 본다`() {
        // 둘 다 틀렸을 때 "지금 상태에서는 못 한다"가 먼저 나가야 한다. 기한만 고쳐 다시
        // 보내봐야 어차피 막히는데, 기한 탓이라고 알려주면 작가를 헛돌게 만든다.
        val gallery = newGallery(GalleryStatus.DRAFT)

        val exception = assertFailsWith<GalleryException> {
            gallery.reopen(selectionDeadline = now.minusDays(1), at = now)
        }

        assertEquals(GalleryErrorCode.INVALID_STATUS_TRANSITION, exception.errorCode)
    }

    @Test
    fun `새 갤러리는 지난 기한을 받지 않는다`() {
        val exception = assertFailsWith<GalleryException> {
            Gallery.create(
                studioId = 1L,
                title = "김철수 · 이영희 본식",
                selectionDeadline = now.minusMinutes(1),
                at = now,
            )
        }

        assertEquals(GalleryErrorCode.INVALID_SELECTION_DEADLINE, exception.errorCode)
    }

    @Test
    fun `새 갤러리는 기한 없이 만들 수 있다`() {
        // 기한은 나중에 정해도 되는 값이라 처음부터 강제하지 않는다.
        val gallery = Gallery.create(studioId = 1L, title = "본식", selectionDeadline = null, at = now)

        assertNull(gallery.selectionDeadline)
        assertEquals(GalleryStatus.DRAFT, gallery.status)
    }

    @Test
    fun `DB에서 되살릴 때는 지난 기한도 그대로 싣는다`() {
        // 검증이 생성자가 아니라 create에 있는 이유다. 생성자에 두면 마감된 갤러리를 읽는
        // 것 자체가 실패한다.
        val gallery = newGallery(GalleryStatus.CLOSED, selectionDeadline = now.minusYears(1))

        assertTrue(gallery.isDeadlinePassed(now))
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

        gallery.changeSelectionDeadline(now.plusDays(1), at = now)

        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `기한을 지우면 무기한이 된다`() {
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now.minusDays(1))

        gallery.changeSelectionDeadline(null, at = now)

        assertNull(gallery.selectionDeadline)
        assertFalse(gallery.isDeadlinePassed(now))
    }

    @Test
    fun `기한을 과거로 옮길 수는 없다`() {
        // 연장의 반대는 단축이지 소급이 아니다. 이미 지난 시각으로 옮기면 그 순간부터 부부는
        // 이유를 알 수 없이 잠긴다.
        val gallery = newGallery(GalleryStatus.OPEN, selectionDeadline = now.plusDays(3))

        val exception = assertFailsWith<GalleryException> {
            gallery.changeSelectionDeadline(now.minusMinutes(1), at = now)
        }

        assertEquals(GalleryErrorCode.INVALID_SELECTION_DEADLINE, exception.errorCode)
        assertEquals(now.plusDays(3), gallery.selectionDeadline)
    }
}
