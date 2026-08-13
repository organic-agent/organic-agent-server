package com.soma.wes.selection.domain

import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 선택 앨범이 스스로 지키는 규칙 — 계약 장수와 제출 잠금.
 *
 * 컨트롤러의 `@Valid`나 서비스의 순서에 기대지 않고 도메인에서 막는지를 본다.
 */
class PhotoSelectionTest {

    private val now: ZonedDateTime = ZonedDateTime.parse("2026-08-08T10:00:00+09:00")

    @Test
    fun `계약 장수를 넘기면 거부한다`() {
        val selection = PhotoSelection(galleryId = 1L)

        val exception = assertFailsWith<SelectionException> {
            selection.requireWithinMax(maxSelectablePhotoCount = 50, countAfterAdd = 51)
        }

        assertEquals(SelectionErrorCode.MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED, exception.errorCode)
    }

    @Test
    fun `정확히 계약 장수만큼은 담을 수 있다`() {
        // 경계값이 막히면 부부는 마지막 한 장을 영영 담지 못한다.
        val selection = PhotoSelection(galleryId = 1L)

        selection.requireWithinMax(maxSelectablePhotoCount = 50, countAfterAdd = 50)
    }

    @Test
    fun `계약 장수가 없으면 몇 장이든 담는다`() {
        // null은 "제한 없음"이다. 장수를 정하지 않고 진행하는 계약도 있다.
        val selection = PhotoSelection(galleryId = 1L)

        selection.requireWithinMax(maxSelectablePhotoCount = null, countAfterAdd = 1_000)
    }

    @Test
    fun `이미 담긴 사진이 하나라도 섞이면 거부한다`() {
        val selection = PhotoSelection(galleryId = 1L)

        val exception = assertFailsWith<SelectionException> {
            selection.requireNotSelected(alreadySelected = setOf(1L, 2L), photoIds = listOf(2L, 3L))
        }

        assertEquals(SelectionErrorCode.PHOTO_ALREADY_SELECTED, exception.errorCode)
    }

    @Test
    fun `겹치지 않으면 통과한다`() {
        val selection = PhotoSelection(galleryId = 1L)

        selection.requireNotSelected(alreadySelected = setOf(1L, 2L), photoIds = listOf(3L, 4L))
    }

    @Test
    fun `제출한 앨범은 고칠 수 없다`() {
        val selection = PhotoSelection(galleryId = 1L)
        selection.submit(selectedCount = 3, at = now)

        val exception = assertFailsWith<SelectionException> { selection.requireEditable() }

        assertEquals(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED, exception.errorCode)
    }

    @Test
    fun `한 장도 고르지 않은 앨범은 제출할 수 없다`() {
        // 덜 고른 것과 고르지 않은 것은 다르다. 뒤쪽은 작가가 받을 것이 없다.
        val selection = PhotoSelection(galleryId = 1L)

        val exception = assertFailsWith<SelectionException> { selection.submit(selectedCount = 0, at = now) }

        assertEquals(SelectionErrorCode.EMPTY_SELECTION, exception.errorCode)
    }

    @Test
    fun `계약 장수에 못 미쳐도 제출한다`() {
        // 50장 계약에 45장만 고르는 일은 실제로 있다. 막을 것은 제출이 아니라 모르고 넘어가는 것이라,
        // 응답이 목표와 현재 장수를 함께 주고 화면이 물어본다.
        val selection = PhotoSelection(galleryId = 1L)

        selection.submit(selectedCount = 45, at = now)

        assertEquals(PhotoSelectionStatus.SUBMITTED, selection.status)
        assertEquals(now, selection.submittedAt)
    }

    @Test
    fun `되돌리면 다시 고를 수 있고 제출 시각도 지워진다`() {
        // status와 submittedAt이 늘 같은 방향을 가리켜야 한다.
        val selection = PhotoSelection(galleryId = 1L)
        selection.submit(selectedCount = 3, at = now)

        selection.withdraw()

        assertEquals(PhotoSelectionStatus.SELECTING, selection.status)
        assertNull(selection.submittedAt)
        selection.requireEditable()
    }

    @Test
    fun `제출되지 않은 앨범은 되돌릴 수 없다`() {
        val selection = PhotoSelection(galleryId = 1L)

        val exception = assertFailsWith<SelectionException> { selection.withdraw() }

        assertEquals(SelectionErrorCode.SELECTION_NOT_SUBMITTED, exception.errorCode)
    }
}
