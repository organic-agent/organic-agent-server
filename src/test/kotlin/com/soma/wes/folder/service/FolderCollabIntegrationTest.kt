package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.repository.PhotoFolderAssignmentRepository
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.service.CollabGuestQueryService
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class FolderCollabIntegrationTest @Autowired constructor(
    private val folderService: FolderService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val assignmentRepository: PhotoFolderAssignmentRepository,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val likeRepository: CollabPhotoLikeRepository,
) {
    @Test
    fun `한 컨셉은 한 링크를 재사용하고 현재 배정을 동적으로 보여준다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 2)
        val concept = folderService.createConcept(
            fixture.galleryId,
            fixture.photographer.requiredId,
            CreateConceptFolderRequest("본식"),
        )
        val detail = folderService.createDetail(
            fixture.galleryId,
            concept.id,
            fixture.photographer.requiredId,
            CreateDetailFolderRequest("메인"),
        )
        folderService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveFolderPhotosRequest(photoIds, detail.id),
        )

        val first = collabSessionService.open(
            fixture.galleryId,
            fixture.member.requiredId,
            OpenCollabSessionRequest(concept.id, "본식 의견"),
        )
        folderService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveFolderPhotosRequest(listOf(photoIds.first()), null),
        )
        val afterUnassign = collabGuestQueryService.listPhotos(first.collabUrl.substringAfterLast('/'), null, 0, 20)
        val reopened = collabSessionService.open(
            fixture.galleryId,
            fixture.member.requiredId,
            OpenCollabSessionRequest(concept.id, "새 이름"),
        )

        assertThat(first.photoCount).isEqualTo(2)
        assertThat(afterUnassign.totalCount).isEqualTo(1)
        assertThat(reopened.sessionId).isEqualTo(first.sessionId)
        assertThat(sessionRepository.count()).isEqualTo(1)
    }

    @Test
    fun `같은 컨셉 안의 이동은 반응을 보존하고 컨셉을 벗어나면 삭제한다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
        val firstConcept = folderService.createConcept(
            fixture.galleryId,
            fixture.photographer.requiredId,
            CreateConceptFolderRequest("본식"),
        )
        val firstDetail = folderService.createDetail(
            fixture.galleryId,
            firstConcept.id,
            fixture.photographer.requiredId,
            CreateDetailFolderRequest("원본"),
        )
        val secondDetail = folderService.createDetail(
            fixture.galleryId,
            firstConcept.id,
            fixture.photographer.requiredId,
            CreateDetailFolderRequest("후보"),
        )
        val otherConcept = folderService.createConcept(
            fixture.galleryId,
            fixture.photographer.requiredId,
            CreateConceptFolderRequest("피로연"),
        )
        val otherDetail = folderService.createDetail(
            fixture.galleryId,
            otherConcept.id,
            fixture.photographer.requiredId,
            CreateDetailFolderRequest("메인"),
        )
        folderService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveFolderPhotosRequest(listOf(photoId), firstDetail.id),
        )
        val session = collabSessionService.open(
            fixture.galleryId,
            fixture.member.requiredId,
            OpenCollabSessionRequest(firstConcept.id, "본식 의견"),
        )
        val token = session.collabUrl.substringAfterLast('/')
        val guest = collabGuestService.enter(token, EnterCollabRequest("친구"))
        collabGuestService.like(token, photoId, guest.guestToken)
        collabGuestService.writeComment(token, photoId, guest.guestToken, WriteCollabCommentRequest("좋아요"))

        folderService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveFolderPhotosRequest(listOf(photoId), secondDetail.id),
        )
        assertThat(likeRepository.count()).isEqualTo(1)
        assertThat(commentRepository.count()).isEqualTo(1)

        folderService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveFolderPhotosRequest(listOf(photoId), otherDetail.id),
        )
        assertThat(likeRepository.count()).isZero()
        assertThat(commentRepository.count()).isZero()
        assertThat(assignmentRepository.findById(photoId).orElseThrow().detailFolderId).isEqualTo(otherDetail.id)
    }
}
