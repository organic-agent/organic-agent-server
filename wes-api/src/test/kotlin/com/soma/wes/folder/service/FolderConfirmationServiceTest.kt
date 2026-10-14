package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

// [REFACTOR-RENAME 2026-09-27] FolderOrganization* → FolderConfirmation* (클래스 이름만 변경, 동작·REST 경로 동일)
// [REFACTOR-CONFIRM 2026-09-27] 호출 메서드 service.save → service.confirm
@IntegrationTest
class FolderConfirmationServiceTest @Autowired constructor(
    private val service: FolderConfirmationService,
    private val folderService: FolderService,
    private val selectionService: PhotoSelectionService,
    private val galleries: GalleryFixture,
    private val galleryRepository: GalleryRepository,
    private val photos: PhotoFixture,
) {
    @Test
    fun `폴더를 한 번 저장한 뒤에만 신규 갤러리에서 선택할 수 있다`() {
        // given
        val fixture = galleries.멤버와_열린_갤러리()
        val gallery = galleryRepository.findById(fixture.galleryId).orElseThrow()
        gallery.photoOrganizationRequired = true
        galleryRepository.saveAndFlush(gallery)
        val concept = folderService.createConcept(fixture.galleryId, fixture.photographer.requiredId, CreateConceptFolderRequest("한복"))
        val ids = photos.업로드된_사진(fixture.galleryId, count = 1)
        assertThatThrownBy { selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = ids)) }
            .isInstanceOf(SelectionException::class.java).extracting("errorCode")
            .isEqualTo(SelectionErrorCode.PHOTO_ORGANIZATION_REQUIRED)

        // when
        val saved = service.confirm(fixture.galleryId, fixture.member.requiredId)
        val selection = selectionService.select(fixture.galleryId, fixture.member.requiredId, SelectPhotosRequest(photoIds = ids))

        // then
        assertThat(saved.single().id).isEqualTo(concept.id)
        assertThat(selection.selectedCount).isEqualTo(1)
        assertThatThrownBy { service.confirm(fixture.galleryId, fixture.member.requiredId) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode")
            .isEqualTo(GalleryErrorCode.FOLDERS_ALREADY_SAVED)
    }

    @Test
    fun `분석 결과가 없으면 저장 마커도 남지 않는다`() {
        // given
        val fixture = galleries.멤버와_열린_갤러리()

        // when & then
        assertThatThrownBy { service.confirm(fixture.galleryId, fixture.member.requiredId) }
            .isInstanceOf(FolderException::class.java).extracting("errorCode")
            .isEqualTo(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
        assertThat(galleryRepository.findById(fixture.galleryId).orElseThrow().foldersSavedAt).isNull()
    }
}
