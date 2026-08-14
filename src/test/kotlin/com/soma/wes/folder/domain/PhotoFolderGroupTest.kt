package com.soma.wes.folder.domain

import com.soma.wes.folder.exception.FolderException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhotoFolderGroupTest {

    @Test
    fun `이름의 앞뒤 공백을 떼어낸다`() {
        val group = PhotoFolderGroup.of(galleryId = 1L, name = "  본식  ")

        assertEquals("본식", group.name)
    }

    @Test
    fun `공백뿐이거나 길이를 넘는 이름은 거부한다`() {
        assertFailsWith<FolderException> { PhotoFolderGroup.of(galleryId = 1L, name = "   ") }
        assertFailsWith<FolderException> {
            PhotoFolderGroup.of(galleryId = 1L, name = "가".repeat(PhotoFolderGroup.MAX_NAME_LENGTH + 1))
        }
    }

    @Test
    fun `이름을 바꿀 때도 같은 규칙이 돈다`() {
        val group = PhotoFolderGroup.of(galleryId = 1L, name = "본식")

        group.rename("  리허설  ")
        assertEquals("리허설", group.name)

        assertFailsWith<FolderException> { group.rename(" ") }
        assertEquals("리허설", group.name)
    }
}
