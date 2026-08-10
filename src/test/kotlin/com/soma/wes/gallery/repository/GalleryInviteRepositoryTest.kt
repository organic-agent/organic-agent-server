package com.soma.wes.gallery.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class GalleryInviteRepositoryTest @Autowired constructor(
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryRepository: GalleryRepository,
    private val studioRepository: StudioRepository,
) {

    private val expiresAt = ZonedDateTime.of(2026, 8, 12, 10, 0, 0, 0, TimeConfig.KST)
    private var sequence = 0L

    private fun newInvite(galleryId: Long, token: String) =
        GalleryInvite(galleryId = galleryId, token = token, expiresAt = expiresAt)

    private fun createGallery(): Long {
        sequence++
        val studio = studioRepository.save(
            Studio(userId = sequence, name = "테스트 스튜디오", galleryUrl = "invite-repository-$sequence"),
        )
        return checkNotNull(
            galleryRepository.save(
                Gallery(studioId = checkNotNull(studio.id), title = "테스트 갤러리"),
            ).id,
        )
    }

    @Test
    fun `토큰으로 초대를 조회한다`() {
        val saved = galleryInviteRepository.save(newInvite(galleryId = createGallery(), token = "token-a"))

        assertEquals(saved.id, galleryInviteRepository.findByToken("token-a")?.id)
    }

    @Test
    fun `없는 토큰을 조회하면 null을 반환한다`() {
        assertNull(galleryInviteRepository.findByToken("unknown"))
    }

    @Test
    fun `같은 토큰을 두 초대가 쓸 수 없다`() {
        // 토큰이 조회 키다. 겹치면 어느 갤러리의 초대인지 가릴 수 없다.
        galleryInviteRepository.save(newInvite(galleryId = createGallery(), token = "token-a"))

        assertFailsWith<DataIntegrityViolationException> {
            galleryInviteRepository.saveAndFlush(newInvite(galleryId = createGallery(), token = "token-a"))
        }
    }

    @Test
    fun `한 갤러리가 초대를 여러 개 가질 수 있다`() {
        // 링크가 유출되면 폐기하고 새로 발급한다. 이전 링크 기록도 남아야 한다.
        val galleryId = createGallery()
        galleryInviteRepository.save(newInvite(galleryId = galleryId, token = "token-a"))
        galleryInviteRepository.save(newInvite(galleryId = galleryId, token = "token-b"))

        // 최근 것이 먼저 온다. 작가 화면이 방금 만든 링크를 맨 위에 놓는다.
        val invites = galleryInviteRepository.findAllByGalleryIdOrderByIdDesc(galleryId)

        assertEquals(listOf("token-b", "token-a"), invites.map { it.token })
    }
}
