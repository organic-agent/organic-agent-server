package com.soma.wes.studio.domain

import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudioTest {

    // 생성자가 아니라 [Studio.create]를 지난다. 생성자는 JPA가 되살릴 때도 지나므로 검증하지 않는다.
    private fun newStudio(galleryUrl: String) =
        Studio.create(userId = 1L, name = "소마 스튜디오", galleryUrl = galleryUrl)

    @Test
    fun `소문자와 숫자, 하이픈으로 된 주소를 받는다`() {
        val studio = newStudio("soma-studio-01")

        assertEquals("soma-studio-01", studio.galleryUrl)
    }

    @Test
    fun `앞뒤 공백과 대문자를 canonical 주소로 정규화한다`() {
        val studio = newStudio("  Soma-Studio  ")

        assertEquals("soma-studio", studio.galleryUrl)
    }

    @Test
    fun `내부 공백이나 슬래시와 언더스코어가 들어간 주소는 받지 않는다`() {
        assertFailsWith<StudioException> { newStudio("soma studio") }
        assertFailsWith<StudioException> { newStudio("soma/studio") }
        assertFailsWith<StudioException> { newStudio("soma_studio") }
    }

    @Test
    fun `비 ASCII 문자가 들어간 주소는 받지 않는다`() {
        assertFailsWith<StudioException> { newStudio("소마-studio") }
        assertFailsWith<StudioException> { newStudio("Kelvin-studio") }
        assertFailsWith<StudioException> { newStudio("\u00A0soma-studio\u00A0") }
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
        assertFailsWith<StudioException> { newStudio("  API  ") }
        assertFailsWith<StudioException> { newStudio("login") }
        assertFailsWith<StudioException> { newStudio("admin") }
    }

    @Test
    fun `주소를 바꿀 때도 정규화와 검증을 적용한다`() {
        val studio = newStudio("soma-studio")

        assertFailsWith<StudioException> { studio.update(name = "이름", galleryUrl = "API") }
        assertEquals("soma-studio", studio.galleryUrl)

        studio.update(name = "이름", galleryUrl = "  New-Studio  ")
        assertEquals("new-studio", studio.galleryUrl)
    }
}
