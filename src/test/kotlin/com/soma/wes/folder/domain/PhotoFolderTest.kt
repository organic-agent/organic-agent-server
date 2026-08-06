package com.soma.wes.folder.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhotoFolderTest {

    @Test
    fun `이름의 앞뒤 공백을 떼어낸다`() {
        val folder = PhotoFolder.of(galleryId = 1L, name = "  본식 - 신부 단독  ")

        assertEquals("본식 - 신부 단독", folder.name)
    }

    @Test
    fun `공백뿐인 이름은 거부한다`() {
        // 화면에 그대로 나가는 값이라, 통과시키면 사용자에게는 이름 없는 폴더로 보인다.
        assertFailsWith<IllegalArgumentException> { PhotoFolder.of(galleryId = 1L, name = "   ") }
    }

    @Test
    fun `컬럼 길이를 넘는 이름은 거부한다`() {
        // DB가 자르거나 거절하기 전에 도메인에서 막는다. 여기서 놓치면 원인이 제약 위반으로만 드러난다.
        val tooLong = "가".repeat(PhotoFolder.MAX_NAME_LENGTH + 1)

        assertFailsWith<IllegalArgumentException> { PhotoFolder.of(galleryId = 1L, name = tooLong) }
    }

    @Test
    fun `이름을 바꿀 때도 같은 규칙이 돈다`() {
        // 생성만 막고 변경을 열어두면 우회 경로가 남는다.
        val folder = PhotoFolder.of(galleryId = 1L, name = "본식")

        folder.rename("  리허설  ")
        assertEquals("리허설", folder.name)

        assertFailsWith<IllegalArgumentException> { folder.rename(" ") }
        assertEquals("리허설", folder.name)
    }
}
