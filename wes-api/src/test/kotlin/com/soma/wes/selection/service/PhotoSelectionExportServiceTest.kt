package com.soma.wes.selection.service

import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.retouch.exception.RetouchException
import com.soma.wes.retouch.exception.RetouchErrorCode
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.request.IssueResultUploadUrlsRequest
import com.soma.wes.retouch.dto.request.CompleteResultsRequest
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.retouch.support.RetouchStorageTestConfig
import com.soma.wes.selection.domain.PhotoSelectionStatus
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@IntegrationTest
@Import(RetouchStorageTestConfig::class)
class PhotoSelectionExportServiceTest @Autowired constructor(
    private val exportService: PhotoSelectionExportService,
    private val selectionService: PhotoSelectionService,
    private val retouchService: RetouchService,
    private val fixtures: PersonalGalleryFixture,
    private val photos: PhotoFixture,
    private val galleries: GalleryRepository,
) {
    @Test
    fun `개인 파트너가 요청서를 내보내면 명시적 제출 없이 보정 단계가 되고 내용은 결정적이다`() {
        // given
        val fixture = fixtures.파트너와_개인_갤러리(target = 1)
        val ids = photos.업로드된_사진(fixture.galleryId, count = 1)
        selectionService.select(fixture.galleryId, fixture.partnerId, SelectPhotosRequest(photoIds = ids))
        retouchService.addPhotos(fixture.galleryId, fixture.ownerId, AddRetouchPhotosRequest(photoIds = ids))
        retouchService.updatePhoto(
            fixture.galleryId, ids.single(), fixture.partnerId,
            UpdateRetouchPhotoRequest(requestText = "=SUM(1,2)\n자연스럽게"),
        )

        // when
        val exported = exportService.export(fixture.galleryId, fixture.partnerId)
        val repeated = exportService.export(fixture.galleryId, fixture.ownerId)

        // then
        assertThat(exported).isEqualTo(repeated)
        assertThat(exported.toString(Charsets.UTF_8)).contains("photo_id,filename,request,point_requests", "'=SUM(1,2)")
        assertThat(galleries.findById(fixture.galleryId).orElseThrow().stage).isEqualTo(GalleryStage.RETOUCH)
        assertThat(selectionService.get(fixture.galleryId, fixture.partnerId).status).isEqualTo(PhotoSelectionStatus.SELECTING)
        assertThatThrownBy { selectionService.deselectPhoto(fixture.galleryId, ids.single(), fixture.partnerId) }
            .isInstanceOf(SelectionException::class.java).extracting("errorCode")
            .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_EXPORTED)
    }

    @Test
    fun `개인 파트너는 받은 보정본을 업로드하고 보내기 없이 바로 확인한다`() {
        // given
        val fixture = fixtures.파트너와_개인_갤러리(target = 1)
        val ids = photos.업로드된_사진(fixture.galleryId, count = 1)
        selectionService.select(fixture.galleryId, fixture.ownerId, SelectPhotosRequest(photoIds = ids))
        exportService.export(fixture.galleryId, fixture.ownerId)
        val issued = retouchService.issueResultUploadUrls(fixture.galleryId, 1, fixture.partnerId,
            IssueResultUploadUrlsRequest(files = listOf(IssueResultUploadUrlsRequest.FileRequest(photoId = ids.single(), contentType = "image/jpeg"))))

        // when
        retouchService.completeResults(fixture.galleryId, 1, fixture.partnerId, CompleteResultsRequest(results =
            issued.uploads.map { CompleteResultsRequest.ResultRequest(photoId = it.photoId, resultKey = it.resultKey, contentType = "image/jpeg") }))

        // then
        val result = retouchService.getRound(fixture.galleryId, 1, fixture.ownerId)
        assertThat(result.photos.single().resultUrl).contains("X-Amz-Signature")
    }

    @Test
    fun `목표 장수에 못 미치면 내보내기와 보정 단계 전이가 모두 거절된다`() {
        // given
        val fixture = fixtures.파트너와_개인_갤러리(target = 2)
        selectionService.select(fixture.galleryId, fixture.ownerId,
            SelectPhotosRequest(photoIds = photos.업로드된_사진(fixture.galleryId, count = 1)))

        // when & then
        assertThatThrownBy { exportService.export(fixture.galleryId, fixture.ownerId) }
            .isInstanceOf(SelectionException::class.java).extracting("errorCode")
            .isEqualTo(SelectionErrorCode.EXACT_TARGET_REQUIRED)
        assertThat(galleries.findById(fixture.galleryId).orElseThrow().stage).isNotEqualTo(GalleryStage.RETOUCH)
    }
    @Test
    fun `개인 갤러리는 제출 전송 확정과 후속 회차를 사용할 수 없다`() {
        // given
        val fixture = fixtures.파트너와_개인_갤러리(target = 1)
        val ids = photos.업로드된_사진(fixture.galleryId, count = 1)
        selectionService.select(fixture.galleryId, fixture.ownerId, SelectPhotosRequest(photoIds = ids))
        assertThatThrownBy { selectionService.submit(fixture.galleryId, fixture.ownerId) }
            .isInstanceOf(SelectionException::class.java).extracting("errorCode")
            .isEqualTo(SelectionErrorCode.PERSONAL_EXPORT_REQUIRED)
        exportService.export(fixture.galleryId, fixture.ownerId)

        // when & then
        val forbiddenActions: List<() -> Any> = listOf(
            { retouchService.submitRequests(fixture.galleryId, 1, fixture.partnerId, SubmitRetouchRequestsRequest()) },
            { retouchService.submitRequests(fixture.galleryId, 2, fixture.partnerId, SubmitRetouchRequestsRequest()) },
            { retouchService.completeRound(fixture.galleryId, 1, fixture.partnerId) },
            { retouchService.confirm(fixture.galleryId, fixture.ownerId) },
            { retouchService.issueResultUploadUrls(fixture.galleryId, 2, fixture.ownerId,
                IssueResultUploadUrlsRequest(files = listOf(IssueResultUploadUrlsRequest.FileRequest(photoId = ids.single(), contentType = "image/jpeg")))) },
        )
        forbiddenActions.forEach { action ->
            assertThatThrownBy { action() }.isInstanceOf(RetouchException::class.java).extracting("errorCode")
                .isEqualTo(RetouchErrorCode.INVALID_ROUND_STATUS)
        }
    }
}
