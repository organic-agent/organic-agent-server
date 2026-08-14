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
    fun `한 갤러리에 살아 있는 초대는 하나뿐이다`() {
        // 여러 개를 살려두면 작가가 어느 링크를 전달했는지 알 수 없어, 퍼진 링크를 거둬들이려
        // 해도 무엇을 폐기할지 모르게 된다. V15의 부분 유니크 인덱스가 이것을 막는다.
        val galleryId = createGallery()
        galleryInviteRepository.save(newInvite(galleryId = galleryId, token = "token-a"))

        assertFailsWith<DataIntegrityViolationException> {
            galleryInviteRepository.saveAndFlush(newInvite(galleryId = galleryId, token = "token-b"))
        }
    }

    @Test
    fun `폐기한 초대는 그 자리를 비워 재발급을 받아준다`() {
        val galleryId = createGallery()
        val previous = galleryInviteRepository.saveAndFlush(newInvite(galleryId = galleryId, token = "token-a"))
        previous.revoke(expiresAt)
        galleryInviteRepository.flush()

        galleryInviteRepository.saveAndFlush(newInvite(galleryId = galleryId, token = "token-b"))

        assertEquals("token-b", galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)?.token)
    }

    @Test
    fun `폐기한 초대는 현재 링크로 조회되지 않는다`() {
        val galleryId = createGallery()
        val invite = galleryInviteRepository.saveAndFlush(newInvite(galleryId = galleryId, token = "token-a"))
        invite.revoke(expiresAt)
        galleryInviteRepository.flush()

        assertNull(galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId))
    }

    @Test
    fun `만료된 초대는 폐기 전까지 현재 링크로 남는다`() {
        // 작가 화면이 "만료됐으니 다시 발급하라"를 보여줄 수 있어야 한다.
        val galleryId = createGallery()
        galleryInviteRepository.saveAndFlush(
            GalleryInvite(galleryId = galleryId, token = "token-a", expiresAt = expiresAt.minusYears(1)),
        )

        assertEquals("token-a", galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)?.token)
    }
}
