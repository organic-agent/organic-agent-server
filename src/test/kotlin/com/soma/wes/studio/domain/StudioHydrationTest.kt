package com.soma.wes.studio.domain

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.repository.StudioRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 주소 검증이 생성자가 아니라 [Studio.of]에 있어야 하는 이유를 고정한다.
 *
 * 예약어 목록과 형식은 앞으로 조여진다. 그때 이미 저장된 행이 되살아나지 못하면 주인은 자기
 * 스튜디오를 열 수도, 그래서 주소를 바꿀 수도 없다. 삭제 경로도 대상을 읽지 못해 막힌다.
 */
@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class StudioHydrationTest @Autowired constructor(
    private val studioRepository: StudioRepository,
    private val entityManager: EntityManager,
) {

    @Test
    fun `지금 규칙으로는 못 만드는 주소를 가진 기존 행도 되살아난다`() {
        // 엔티티를 거치지 않고 넣는다. 예약어이자 대문자라 Studio.of는 받지 않는 값이다.
        entityManager.createNativeQuery(
            "INSERT INTO studios (user_id, name, gallery_url) VALUES (:userId, :name, :galleryUrl)",
        ).setParameter("userId", LEGACY_USER_ID)
            .setParameter("name", "규칙이 조여지기 전에 만들어진 스튜디오")
            .setParameter("galleryUrl", LEGACY_GALLERY_URL)
            .executeUpdate()
        entityManager.flush()
        entityManager.clear()

        val studio = studioRepository.findByUserId(LEGACY_USER_ID)

        assertNotNull(studio)
        // 되살리면서 조용히 정규화하지도 않는다. 그랬다면 읽기만 해도 UPDATE가 나간다.
        assertEquals(LEGACY_GALLERY_URL, studio.galleryUrl)
    }

    @Test
    fun `그 행의 주인은 주소를 유효한 값으로 고칠 수 있다`() {
        entityManager.createNativeQuery(
            "INSERT INTO studios (user_id, name, gallery_url) VALUES (:userId, :name, :galleryUrl)",
        ).setParameter("userId", LEGACY_USER_ID)
            .setParameter("name", "규칙이 조여지기 전에 만들어진 스튜디오")
            .setParameter("galleryUrl", LEGACY_GALLERY_URL)
            .executeUpdate()
        entityManager.flush()
        entityManager.clear()

        val studio = assertNotNull(studioRepository.findByUserId(LEGACY_USER_ID))
        studio.update(name = "고친 스튜디오", galleryUrl = "  Fixed-Studio  ")

        assertEquals("fixed-studio", studio.galleryUrl)
    }

    companion object {
        private const val LEGACY_USER_ID = 9001L
        private const val LEGACY_GALLERY_URL = "ADMIN"
    }
}
