package com.soma.wes.category.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.dto.request.CreateDetailFolderRequest
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.dto.response.CategorizationJobResponse
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.dto.response.DetailFolderResponse
import com.soma.wes.category.service.CategorizationService
import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.category.service.CategoryService
import jakarta.validation.Valid
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "[Category]", description = "Concept/Detail 카테고리와 비동기 분류 API")
class CategoryController(
    private val categoryService: CategoryService,
    private val categorizationService: CategorizationService,
    private val aiCategoryFolderService: AiCategoryFolderService,
) {
    @PostMapping("/concept-folders")
    fun createConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CreateConceptFolderRequest,
    ): ResponseEntity<ConceptFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(categoryService.createConcept(galleryId, loginUser.id, request))
    }

    @GetMapping("/concept-folders")
    @Operation(
        summary = "Concept/Detail 카테고리 조회",
        description = "폐기된 photo-clusters API 대신 Concept와 하위 Detail 카테고리 구조를 반환한다.",
    )
    fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.ok(categoryService.list(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/ai")
    @Operation(
        summary = "AI 분석 결과로 카테고리 생성",
        description = "클러스터 공개 API를 복원하지 않고 분석 결과를 Concept/Detail 카테고리로 materialize한다.",
    )
    fun createFromAnalysis(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(aiCategoryFolderService.createFromAnalysis(galleryId, loginUser.id))
    }

    @PostMapping("/concept-folders/{conceptId}/detail-folders")
    fun createDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @Valid @RequestBody request: CreateDetailFolderRequest,
    ): ResponseEntity<DetailFolderResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(categoryService.createDetail(galleryId, conceptId, loginUser.id, request))
    }

    @PostMapping("/category-assignments/move")
    fun movePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: MoveCategoryPhotosRequest,
    ): ResponseEntity<Unit> {
        categoryService.movePhotos(galleryId, loginUser.id, request)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/concept-folders/{conceptId}/detail-folders/{detailId}")
    fun deleteDetail(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
        @PathVariable detailId: Long,
    ): ResponseEntity<Unit> {
        categoryService.deleteDetail(galleryId, conceptId, detailId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/concept-folders/{conceptId}")
    fun deleteConcept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable conceptId: Long,
    ): ResponseEntity<Unit> {
        categoryService.deleteConcept(galleryId, conceptId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/categorization-jobs")
    @Operation(summary = "사진 카테고리 분류 작업 시작")
    fun runCategorization(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<CategorizationJobResponse> {
        return ResponseEntity.status(HttpStatus.CREATED).body(categorizationService.run(galleryId, loginUser.id))
    }

    @GetMapping("/categorization-jobs/latest")
    @Operation(summary = "최근 사진 카테고리 분류 작업 조회")
    fun latestCategorization(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<CategorizationJobResponse> {
        val result = categorizationService.latest(galleryId, loginUser.id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(result)
    }
}
