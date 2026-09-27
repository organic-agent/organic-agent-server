package com.soma.wes.category.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.category.controller.docs.CategoryControllerDocs
import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.dto.response.DetailFolderResponse
import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.category.service.CategoryService
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
class CategoryController(
    private val categoryService: CategoryService,
    private val aiCategoryFolderService: AiCategoryFolderService,
) : CategoryControllerDocs {

    @PostMapping("/concept-folders")
    override fun createConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CreateConceptFolderRequest,
    ): ResponseEntity<ConceptFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(categoryService.createConcept(galleryId, loginUser.id, request))
    }

    @GetMapping("/concept-folders")
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.ok(categoryService.list(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/ai")
    override fun createFromAnalysis(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(aiCategoryFolderService.createFromAnalysis(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/{conceptId}/detail-folders")
    override fun createDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @Valid @RequestBody request: CreateDetailFolderRequest,
    ): ResponseEntity<DetailFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(categoryService.createDetail(galleryId, conceptId, loginUser.id, request))
    }

    @PostMapping("/category-assignments/move")
    override fun movePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: MoveCategoryPhotosRequest,
    ): ResponseEntity<Unit> {
        categoryService.movePhotos(galleryId, loginUser.id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/concept-folders/{conceptId}/detail-folders/{detailId}")
    override fun deleteDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @PathVariable detailId: Long,
    ): ResponseEntity<Unit> {
        categoryService.deleteDetail(galleryId, conceptId, detailId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/concept-folders/{conceptId}")
    override fun deleteConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
    ): ResponseEntity<Unit> {
        categoryService.deleteConcept(galleryId, conceptId, loginUser.id)
        return ResponseEntity.noContent().build()
    }
}
