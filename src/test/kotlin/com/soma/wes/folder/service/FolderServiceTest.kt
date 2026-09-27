package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
@DisplayName("폴더를 만들 때")
class FolderServiceTest @Autowired constructor(
    private val folderService: FolderService,
    private val galleryFixture: GalleryFixture,
) {

    /** 폴더를 지워도 남은 폴더의 sortOrder는 당겨지지 않는다 — 개수로 정렬 순서를 정하면 새 폴더가 기존 폴더 사이에 끼었다. */
    @Test
    fun `앞쪽 폴더를 지운 뒤에도 새 폴더는 맨 뒤에 붙는다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val userId = fixture.photographer.requiredId
        val (first, second, third, fourth) = listOf("A", "B", "C", "D").map { name ->
            folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest(name))
        }
        folderService.deleteConcept(fixture.galleryId, first.id, userId)
        folderService.deleteConcept(fixture.galleryId, second.id, userId)

        // when
        folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("E"))

        // then
        assertThat(folderService.list(fixture.galleryId, userId).map { it.name })
            .containsExactly(third.name, fourth.name, "E")
    }

    @Test
    fun `앞쪽 세부 폴더를 지운 뒤에도 새 세부 폴더는 맨 뒤에 붙는다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val userId = fixture.photographer.requiredId
        val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("야외"))
        val (first, second, third, fourth) = listOf("A", "B", "C", "D").map { name ->
            folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest(name))
        }
        folderService.deleteDetail(fixture.galleryId, concept.id, first.id, userId)
        folderService.deleteDetail(fixture.galleryId, concept.id, second.id, userId)

        // when
        folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("E"))

        // then
        assertThat(folderService.list(fixture.galleryId, userId).single().details.map { it.name })
            .containsExactly(third.name, fourth.name, "E")
    }
}
