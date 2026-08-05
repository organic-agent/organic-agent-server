package com.soma.wes.studio.domain

import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioTest {

    private fun newStudio(galleryUrl: String) =
        Studio(userId = 1L, name = "소마 스튜디오", galleryUrl = galleryUrl)

    @Test
    fun `소문자와 숫자, 하이픈으로 된 주소를 받는다`() {
        val studio = newStudio("soma-studio-01")

        assertEquals("soma-studio-01", studio.galleryUrl)
    }

    @Test
    fun `대문자가 섞인 주소는 받지 않는다`() {
        // 허용하면 대소문자만 다른 주소가 서로 다른 스튜디오로 잡혀 유니크 제약이 무의미해진다.
        val exception = assertFailsWith<StudioException> { newStudio("Soma-Studio") }

        assertEquals(StudioErrorCode.INVALID_GALLERY_URL, exception.errorCode)
    }

    @Test
    fun `공백이나 슬래시가 들어간 주소는 받지 않는다`() {
        assertFailsWith<StudioException> { newStudio("soma studio") }
        assertFailsWith<StudioException> { newStudio("soma/studio") }
    }

    @Test
    fun `너무 짧거나 긴 주소는 받지 않는다`() {
        assertFailsWith<StudioException> { newStudio("ab") }
        assertFailsWith<StudioException> { newStudio("a".repeat(51)) }
    }

    @Test
    fun `하이픈으로 시작하거나 끝나는 주소는 받지 않는다`() {
        assertFailsWith<StudioException> { newStudio("-soma") }
        assertFailsWith<StudioException> { newStudio("soma-") }
    }

    @Test
    fun `서비스가 먼저 쓰는 경로는 선점할 수 없다`() {
        // 도메인 바로 아래에 붙는 주소라, 선점당하면 해당 경로로 못 간다.
        assertFailsWith<StudioException> { newStudio("api") }
        assertFailsWith<StudioException> { newStudio("login") }
        assertFailsWith<StudioException> { newStudio("admin") }
    }

    @Test
    fun `주소를 바꿀 때도 같은 규칙을 적용한다`() {
        val studio = newStudio("soma-studio")

        assertFailsWith<StudioException> { studio.update(name = "이름", galleryUrl = "API") }
        assertEquals("soma-studio", studio.galleryUrl)
    }
}
