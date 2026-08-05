package com.soma.wes.gallery.repository

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.global.config.TimeConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class GalleryMemberRepositoryTest @Autowired constructor(
    private val galleryMemberRepository: GalleryMemberRepository,
) {

    @Test
    fun `갤러리와 사용자로 멤버를 조회한다`() {
        val saved = galleryMemberRepository.save(GalleryMember(galleryId = 1L, userId = 100L))

        val found = galleryMemberRepository.findByGalleryIdAndUserId(1L, 100L)

        assertEquals(saved.id, found?.id)
    }

    @Test
    fun `초대받지 않은 사용자를 조회하면 null을 반환한다`() {
        assertNull(galleryMemberRepository.findByGalleryIdAndUserId(1L, 999L))
    }

    @Test
    fun `같은 갤러리에 같은 사용자를 두 번 초대할 수 없다`() {
        // 중복 초대를 막지 않으면 같은 사람의 멤버 행이 둘이 되고,
        // 수락 여부가 서로 다를 때 어느 행으로 권한을 판단할지 알 수 없게 된다.
        galleryMemberRepository.save(GalleryMember(galleryId = 1L, userId = 100L))

        assertFailsWith<DataIntegrityViolationException> {
            galleryMemberRepository.saveAndFlush(GalleryMember(galleryId = 1L, userId = 100L))
        }
    }

    @Test
    fun `같은 사용자가 서로 다른 갤러리의 멤버가 될 수 있다`() {
        // 본식과 리마인드 촬영처럼 같은 부부의 갤러리가 여러 개일 수 있다.
        galleryMemberRepository.save(GalleryMember(galleryId = 1L, userId = 100L))
        galleryMemberRepository.save(GalleryMember(galleryId = 2L, userId = 100L))

        assertEquals(2, galleryMemberRepository.findAllByUserId(100L).size)
    }
}
