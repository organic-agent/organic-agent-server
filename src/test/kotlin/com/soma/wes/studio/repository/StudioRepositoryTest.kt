package com.soma.wes.studio.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class StudioRepositoryTest @Autowired constructor(
    private val studioRepository: StudioRepository,
) {

    private fun newStudio(userId: Long, galleryUrl: String) =
        Studio(userId = userId, name = "소마 스튜디오", galleryUrl = galleryUrl)

    @Test
    fun `사용자로 스튜디오를 조회한다`() {
        val saved = studioRepository.save(newStudio(userId = 10L, galleryUrl = "soma-studio"))

        assertEquals(saved.id, studioRepository.findByUserId(10L)?.id)
    }

    @Test
    fun `한 작가가 스튜디오를 두 개 만들 수 없다`() {
        studioRepository.save(newStudio(userId = 10L, galleryUrl = "soma-studio"))

        assertFailsWith<DataIntegrityViolationException> {
            studioRepository.saveAndFlush(newStudio(userId = 10L, galleryUrl = "soma-studio-2"))
        }
    }

    @Test
    fun `같은 갤러리 주소를 두 스튜디오가 쓸 수 없다`() {
        // 공개 주소가 겹치면 어느 스튜디오를 보여줄지 가릴 수 없다.
        studioRepository.save(newStudio(userId = 10L, galleryUrl = "soma-studio"))

        assertFailsWith<DataIntegrityViolationException> {
            studioRepository.saveAndFlush(newStudio(userId = 20L, galleryUrl = "soma-studio"))
        }
    }

    @Test
    fun `갤러리 주소로 스튜디오를 조회한다`() {
        val saved = studioRepository.save(newStudio(userId = 10L, galleryUrl = "soma-studio"))

        assertEquals(saved.id, studioRepository.findByGalleryUrl("soma-studio")?.id)
    }

    @Test
    fun `스튜디오를 만들지 않은 사용자는 온보딩 전이다`() {
        assertFalse(studioRepository.existsByUserId(999L))
    }
}
