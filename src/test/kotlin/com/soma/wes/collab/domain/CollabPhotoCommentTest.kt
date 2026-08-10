package com.soma.wes.collab.domain

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollabPhotoCommentTest {

    @Test
    fun `앞뒤 공백은 떼고 저장한다`() {
        assertEquals("예쁘다", CollabPhotoComment.requireValidContent("  예쁘다\n"))
    }

    @Test
    fun `빈 댓글은 받지 않는다`() {
        val exception = assertFailsWith<CollabException> {
            CollabPhotoComment.requireValidContent(" \n ")
        }

        assertEquals(CollabErrorCode.INVALID_COMMENT, exception.errorCode)
    }

    @Test
    fun `너무 긴 댓글은 받지 않는다`() {
        CollabPhotoComment.requireValidContent("가".repeat(CollabPhotoComment.MAX_CONTENT_LENGTH))

        assertFailsWith<CollabException> {
            CollabPhotoComment.requireValidContent("가".repeat(CollabPhotoComment.MAX_CONTENT_LENGTH + 1))
        }
    }

    @Test
    fun `쓴 사람만 자기 댓글로 본다`() {
        val comment = CollabPhotoComment(collabPhotoId = 1L, collabGuestId = 10L, content = "내 댓글")

        assertTrue(comment.isWrittenBy(10L))
        assertFalse(comment.isWrittenBy(11L))
    }
}
