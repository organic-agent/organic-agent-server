package com.soma.wes.collab.fixture

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.service.FolderService
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.ZonedDateTime

@Component
class CollabFixture(
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val folderService: FolderService,
    private val sessionService: CollabSessionService,
    private val guestService: CollabGuestService,
    private val jdbcTemplate: JdbcTemplate,
) {
    fun 사진이_있는_세션(gallery: OpenGallery = galleryFixture.멤버와_열린_갤러리()): SharedCollab {
        val photoId = photoFixture.업로드된_사진(gallery.galleryId, 1).single()
        val concept = folderService.createConcept(
            gallery.galleryId,
            gallery.photographer.requiredId,
            CreateConceptFolderRequest(name = "본식"),
        )
        val detail = folderService.createDetail(
            gallery.galleryId,
            concept.id,
            gallery.photographer.requiredId,
            CreateDetailFolderRequest(name = "함께"),
        )
        folderService.movePhotos(
            gallery.galleryId,
            gallery.photographer.requiredId,
            MoveFolderPhotosRequest(photoIds = listOf(photoId), targetDetailFolderId = detail.id),
        )
        val session = sessionService.open(
            gallery.galleryId,
            gallery.member.requiredId,
            OpenCollabSessionRequest(conceptFolderId = concept.id, name = "본식 의견"),
        )
        return SharedCollab(gallery = gallery, session = session, conceptId = concept.id, detailId = detail.id, photoId = photoId)
    }

    /** [photoIds]를 담은 공유폴더를 열고, 하객 한 명이 들어와 그 사진들에 좋아요를 누른 상태. */
    fun 하객_좋아요(gallery: OpenGallery, photoIds: List<Long>) {
        val session = sessionService.open(
            gallery.galleryId,
            gallery.member.requiredId,
            OpenCollabSessionRequest(name = "하객 의견", photoIds = photoIds),
        )
        val collabToken = session.collabUrl.substringAfterLast('/')
        val guest = guestService.enter(collabToken, EnterCollabRequest(nickname = "하객"))
        photoIds.forEach { photoId -> guestService.like(collabToken, photoId, guest.guestToken) }
    }

    /** 공유폴더를 휴지통에 넣은 상태. 삭제는 trash 도메인의 SQL이 하는 일이라 엔티티에 쓰는 메서드가 없다. */
    fun 세션_삭제됨(sessionId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE collab_sessions SET deleted_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            at.toOffsetDateTime(), sessionId,
        )
    }

    /** 하객 댓글이 지워진 상태. */
    fun 댓글_삭제됨(commentId: Long, at: ZonedDateTime) {
        jdbcTemplate.update(
            "UPDATE collab_photo_comments SET deleted_at = ?, version = version + 1, updated_at = now() WHERE id = ?",
            at.toOffsetDateTime(), commentId,
        )
    }
}

data class SharedCollab(
    val gallery: OpenGallery,
    val session: CollabSessionResponse,
    /** 공유폴더를 만들 때 범위로 쓴 컨셉. 공유폴더는 이 컨셉과 연결되지 않는다 — 만든 순간의 사진만 담았다. */
    val conceptId: Long,
    val detailId: Long,
    val photoId: Long,
) {
    val galleryId: Long get() = gallery.galleryId
    val token: String get() = session.collabUrl.substringAfterLast('/')
}
