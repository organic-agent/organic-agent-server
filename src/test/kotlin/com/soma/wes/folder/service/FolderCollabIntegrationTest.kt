package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
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

/**
 * 공유폴더는 컨셉·세부 폴더와 따로 산다. 폴더에서 공유폴더를 만드는 것은 사진을 하나하나 고르기 귀찮아서일 뿐이고,
 * 만든 뒤 폴더에서 일어나는 일(이동·합치기·되돌리기·삭제)은 공유폴더의 사진과 하객 반응에 닿지 않는다.
 */
@IntegrationTest
class FolderCollabIntegrationTest @Autowired constructor(
    private val folderService: FolderService,
    private val collabSessionService: CollabSessionService,
    private val collabGuestService: CollabGuestService,
    private val collabGuestQueryService: CollabGuestQueryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val likeRepository: CollabPhotoLikeRepository,
) {
    @Test
    fun `컨셉으로 만든 공유폴더는 만든 순간의 사진을 담고 이후 배정을 따라가지 않는다`() {
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
        assertThat(afterUnassign.totalCount).isEqualTo(2)
        // 다시 만들면 그때의 사진으로 새 공유폴더가 생긴다.
        assertThat(reopened.sessionId).isNotEqualTo(first.sessionId)
        assertThat(reopened.photoCount).isEqualTo(1)
        assertThat(sessionRepository.count()).isEqualTo(2)
    }

    @Test
    fun `사진을 다른 컨셉으로 옮기거나 합치고 되돌려도 하객 반응이 남는다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val userId = fixture.photographer.requiredId
        val photoId = photoFixture.업로드된_사진(fixture.galleryId, 1).single()
        val ceremony = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
        val entrance1 = folderService.createDetail(fixture.galleryId, ceremony.id, userId, CreateDetailFolderRequest("입장 1"))
        val entrance2 = folderService.createDetail(fixture.galleryId, ceremony.id, userId, CreateDetailFolderRequest("입장 2"))
        val reception = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("피로연"))
        val receptionDetail = folderService.createDetail(fixture.galleryId, reception.id, userId, CreateDetailFolderRequest("메인"))
        folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(listOf(photoId), entrance1.id))
        val session = collabSessionService.open(
            fixture.galleryId,
            fixture.member.requiredId,
            OpenCollabSessionRequest(ceremony.id, "본식 의견"),
        )
        val token = session.collabUrl.substringAfterLast('/')
        val guest = collabGuestService.enter(token, EnterCollabRequest("친구"))
        collabGuestService.like(token, photoId, guest.guestToken)
        collabGuestService.writeComment(token, photoId, guest.guestToken, WriteCollabCommentRequest("좋아요"))

        // when
        folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(listOf(photoId), receptionDetail.id))
        folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(listOf(photoId), entrance1.id))
        folderService.mergeDetail(fixture.galleryId, ceremony.id, entrance1.id, userId, MergeDetailFolderRequest(entrance2.id))
        val merged = folderService.mergeDetail(
            fixture.galleryId, ceremony.id, entrance2.id, userId, MergeDetailFolderRequest(receptionDetail.id),
        )
        folderService.undoMerge(fixture.galleryId, merged.mergeId, userId)
        folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(listOf(photoId), null))

        // then
        assertThat(assignmentRepository.findById(photoId)).isEmpty()
        assertThat(likeRepository.count()).isEqualTo(1)
        assertThat(commentRepository.count()).isEqualTo(1)
        assertThat(collabGuestQueryService.listPhotos(token, guest.guestToken, 0, 20).contents.single().liked).isTrue()
    }
}
