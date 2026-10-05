package com.soma.wes.folder.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.controller.docs.FolderControllerDocs
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.service.FolderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}")
class FolderController(
    private val folderService: FolderService,
) : FolderControllerDocs {

    @PostMapping("/concept-folders")
    override fun createConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CreateConceptFolderRequest,
    ): ResponseEntity<ConceptFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(folderService.createConcept(galleryId, loginUser.id, request))
    }

    @GetMapping("/concept-folders")
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.ok(folderService.list(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/ai")
    override fun createFromAnalysis(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(folderService.createFromAnalysis(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/{conceptId}/detail-folders")
    override fun createDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @Valid @RequestBody request: CreateDetailFolderRequest,
    ): ResponseEntity<DetailFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(folderService.createDetail(galleryId, conceptId, loginUser.id, request))
    }

    @PostMapping("/category-assignments/move")
    override fun movePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: MoveFolderPhotosRequest,
    ): ResponseEntity<Unit> {
        folderService.movePhotos(galleryId, loginUser.id, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/concept-folders/{conceptId}/detail-folders/{detailId}/merge")
    override fun mergeDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @PathVariable detailId: Long,
        @Valid @RequestBody request: MergeDetailFolderRequest,
    ): ResponseEntity<DetailFolderResponse> {
        return ResponseEntity.ok(folderService.mergeDetail(galleryId, conceptId, detailId, loginUser.id, request))
    }

    @DeleteMapping("/concept-folders/{conceptId}/detail-folders/{detailId}")
    override fun deleteDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @PathVariable detailId: Long,
    ): ResponseEntity<Unit> {
        folderService.deleteDetail(galleryId, conceptId, detailId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/concept-folders/{conceptId}")
    override fun deleteConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
    ): ResponseEntity<Unit> {
        folderService.deleteConcept(galleryId, conceptId, loginUser.id)
        return ResponseEntity.noContent().build()
    }
}
