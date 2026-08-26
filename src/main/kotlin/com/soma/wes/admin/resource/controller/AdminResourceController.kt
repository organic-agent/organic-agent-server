package com.soma.wes.admin.resource.controller

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.AdminReprocessResponse
import com.soma.wes.admin.resource.dto.AdminReasonRequest
import com.soma.wes.admin.resource.dto.AdminOperationsOverviewResponse
import com.soma.wes.admin.resource.dto.AdminObservabilityLinksResponse
import com.soma.wes.admin.resource.dto.AdminPhotoAccessRequest
import com.soma.wes.admin.resource.dto.AdminPhotoAccessResponse
import com.soma.wes.admin.resource.dto.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.AdminResourceResponse
import com.soma.wes.admin.resource.dto.AdminSystemSettingsResponse
import com.soma.wes.admin.resource.dto.AdminTrashBatchResponse
import com.soma.wes.admin.resource.dto.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminReprocessService
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminOperationsOverviewService
import com.soma.wes.admin.resource.service.AdminObservabilityLinkService
import com.soma.wes.admin.resource.service.AdminPhotoAccessService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminResourceSuspensionService
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminSystemSettingsService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/admin/v1")
class AdminResourceController(
    private val resourceService: AdminResourceService,
    private val reprocessService: AdminReprocessService,
    private val systemSettingsService: AdminSystemSettingsService,
    private val operationsOverviewService: AdminOperationsOverviewService,
    private val resourceContextService: AdminResourceContextService,
    private val photoAccessService: AdminPhotoAccessService,
    private val observabilityLinkService: AdminObservabilityLinkService,
    private val cascadeTrashService: AdminCascadeTrashService,
    private val suspensionService: AdminResourceSuspensionService,
) {

    @GetMapping("/operations/overview")
    fun getOperationsOverview(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminOperationsOverviewResponse> = ResponseEntity.ok(operationsOverviewService.get())

    @GetMapping("/operations/observability-links")
    fun observabilityLinks(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam correlationId: String,
    ): ResponseEntity<AdminObservabilityLinksResponse> = ResponseEntity.ok(
        observabilityLinkService.links(correlationId),
    )

    @GetMapping("/operations/trash")
    fun listTrash(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<List<AdminTrashBatchResponse>> = ResponseEntity.ok(cascadeTrashService.list())

    @PostMapping("/operations/trash/{batchId}/restore")
    fun restoreTrashBatch(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable batchId: Long,
        @Valid @RequestBody request: AdminReasonRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTrashBatchResponse> = ResponseEntity.ok(
        cascadeTrashService.restoreBatch(loginUser.id, batchId, request, servletRequest.remoteAddr),
    )

    @GetMapping("/resources")
    fun search(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) types: Set<AdminResourceType>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<AdminResourcePageResponse> =
        ResponseEntity.ok(resourceService.search(query, types.orEmpty(), page, size))

    @GetMapping("/resources/{type}/{id}")
    fun get(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
    ): ResponseEntity<AdminResourceResponse> = ResponseEntity.ok(resourceService.get(type, id))

    @GetMapping("/resources/{type}/{id}/context")
    fun getContext(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
    ): ResponseEntity<AdminResourceContextResponse> = ResponseEntity.ok(resourceContextService.get(type, id))

    @PostMapping("/resources/PHOTO/{id}/original-access")
    fun accessOriginalPhoto(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminPhotoAccessRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminPhotoAccessResponse> = ResponseEntity.ok(
        photoAccessService.access(loginUser.id, id, request, servletRequest.remoteAddr),
    )

    @PostMapping("/resources/{type}")
    fun create(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @Valid @RequestBody request: CreateAdminResourceRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            resourceService.create(loginUser.id, type, request, servletRequest.remoteAddr),
        )

    @PatchMapping("/resources/{type}/{id}")
    fun update(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateAdminResourceRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> = ResponseEntity.ok(
        resourceService.update(loginUser.id, type, id, request, servletRequest.remoteAddr),
    )

    @DeleteMapping("/resources/{type}/{id}")
    fun delete(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        cascadeTrashService.delete(loginUser.id, type, id, request, servletRequest.remoteAddr)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/resources/{type}/{id}/restore")
    fun restore(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> = ResponseEntity.ok(
        cascadeTrashService.restoreRoot(loginUser.id, type, id, request, servletRequest.remoteAddr),
    )

    @PostMapping("/resources/{type}/{id}/suspend")
    fun suspend(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> = ResponseEntity.ok(
        suspensionService.suspend(loginUser.id, type, id, request, servletRequest.remoteAddr),
    )

    @PostMapping("/resources/{type}/{id}/activate")
    fun activate(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> = ResponseEntity.ok(
        suspensionService.activate(loginUser.id, type, id, request, servletRequest.remoteAddr),
    )

    @PostMapping("/resources/{type}/{id}/reprocess")
    fun reprocess(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminReprocessRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminReprocessResponse> = ResponseEntity.accepted().body(
        reprocessService.reprocess(loginUser.id, type, id, request, servletRequest.remoteAddr),
    )

    @GetMapping("/system-settings")
    fun getSystemSettings(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminSystemSettingsResponse> = ResponseEntity.ok(systemSettingsService.get())
}
