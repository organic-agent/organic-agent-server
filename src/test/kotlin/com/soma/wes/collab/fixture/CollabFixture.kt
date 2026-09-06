package com.soma.wes.collab.fixture

import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import org.springframework.stereotype.Component

@Component
class CollabFixture(
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val categoryService: CategoryService,
    private val sessionService: CollabSessionService,
) {
    fun 사진이_있는_세션(gallery: OpenGallery = galleryFixture.멤버와_열린_갤러리()): SharedCollab {
        val photoId = photoFixture.업로드된_사진(gallery.galleryId, 1).single()
        val concept = categoryService.createConcept(
            gallery.galleryId,
            gallery.photographer.requiredId,
            CreateConceptFolderRequest(name = "본식"),
        )
        val detail = categoryService.createDetail(
            gallery.galleryId,
            concept.id,
            gallery.photographer.requiredId,
            CreateDetailFolderRequest(name = "함께"),
        )
        categoryService.movePhotos(
            gallery.galleryId,
            gallery.photographer.requiredId,
            MoveCategoryPhotosRequest(photoIds = listOf(photoId), targetDetailFolderId = detail.id),
        )
        val session = sessionService.open(
            gallery.galleryId,
            gallery.photographer.requiredId,
            OpenCollabSessionRequest(conceptFolderId = concept.id, name = "본식 의견"),
        )
        return SharedCollab(gallery = gallery, session = session, detailId = detail.id, photoId = photoId)
    }
}

data class SharedCollab(
    val gallery: OpenGallery,
    val session: CollabSessionResponse,
    val detailId: Long,
    val photoId: Long,
) {
    val galleryId: Long get() = gallery.galleryId
    val token: String get() = session.collabUrl.substringAfterLast('/')
}
