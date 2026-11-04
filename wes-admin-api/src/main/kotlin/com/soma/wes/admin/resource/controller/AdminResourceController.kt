package com.soma.wes.admin.resource.controller

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.controller.docs.AdminResourceControllerDocs
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.dto.response.AdminAnalysisFailuresResponse
import com.soma.wes.admin.resource.dto.request.AdminReprocessRequest
import com.soma.wes.admin.resource.dto.response.AdminReprocessResponse
import com.soma.wes.admin.resource.dto.response.AdminOperationsOverviewResponse
import com.soma.wes.admin.resource.dto.response.AdminObservabilityLinksResponse
import com.soma.wes.admin.resource.dto.request.AdminPhotoAccessRequest
import com.soma.wes.admin.resource.dto.response.AdminPhotoAccessResponse
import com.soma.wes.admin.resource.dto.request.AdminRetouchArtifactAccessRequest
import com.soma.wes.admin.resource.dto.response.AdminRetouchArtifactAccessResponse
import com.soma.wes.admin.resource.domain.AdminRetouchArtifactType
import com.soma.wes.admin.resource.dto.response.AdminResourcePageResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceContextResponse
import com.soma.wes.admin.resource.dto.response.AdminResourceResponse
import com.soma.wes.admin.resource.dto.response.AdminSystemSettingsResponse
import com.soma.wes.admin.resource.dto.response.AdminTrashBatchResponse
import com.soma.wes.admin.resource.dto.response.AdminChildTrashResponse
import com.soma.wes.admin.resource.dto.request.AdminWorkflowRequest
import com.soma.wes.admin.resource.dto.response.AdminWorkflowResponse
import com.soma.wes.admin.resource.dto.request.ChangeAdminResourceStateRequest
import com.soma.wes.admin.resource.dto.request.CreateAdminResourceRequest
import com.soma.wes.admin.resource.dto.request.UpdateAdminResourceRequest
import com.soma.wes.admin.resource.service.AdminReprocessService
import com.soma.wes.admin.resource.service.AdminCascadeTrashService
import com.soma.wes.admin.resource.service.AdminChildTrashService
import com.soma.wes.admin.resource.service.AdminOperationsOverviewService
import com.soma.wes.admin.resource.service.AdminObservabilityLinkService
import com.soma.wes.admin.resource.service.AdminPhotoAccessService
import com.soma.wes.admin.resource.service.AdminRetouchArtifactAccessService
import com.soma.wes.admin.resource.service.AdminResourceService
import com.soma.wes.admin.resource.service.AdminResourceSuspensionService
import com.soma.wes.admin.resource.service.AdminResourceContextService
import com.soma.wes.admin.resource.service.AdminSystemSettingsService
import com.soma.wes.admin.resource.service.AdminWorkflowService
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
    private val retouchArtifactAccessService: AdminRetouchArtifactAccessService,
    private val observabilityLinkService: AdminObservabilityLinkService,
    private val cascadeTrashService: AdminCascadeTrashService,
    private val childTrashService: AdminChildTrashService,
    private val suspensionService: AdminResourceSuspensionService,
    private val workflowService: AdminWorkflowService,
) : AdminResourceControllerDocs {

    private companion object {
        val IMMEDIATE_DELETE_TYPES = setOf(
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT,
            AdminResourceType.PHOTO_RATING,
        )
    }

    @GetMapping("/operations/overview")
    override fun getOperationsOverview(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminOperationsOverviewResponse> {
        return ResponseEntity.ok(operationsOverviewService.get())
    }

    @GetMapping("/operations/observability-links")
    override fun observabilityLinks(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam correlationId: String,
    ): ResponseEntity<AdminObservabilityLinksResponse> {
        return ResponseEntity.ok(
            observabilityLinkService.links(correlationId),
        )
    }

    @GetMapping("/operations/trash")
    override fun listTrash(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<List<AdminTrashBatchResponse>> {
        return ResponseEntity.ok(cascadeTrashService.list())
    }

    @GetMapping("/operations/trash/children")
    override fun listChildTrash(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<List<AdminChildTrashResponse>> {
        return ResponseEntity.ok(childTrashService.list())
    }

    @PostMapping("/operations/trash/{batchId}/restore")
    override fun restoreTrashBatch(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable batchId: Long,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminTrashBatchResponse> {
        return ResponseEntity.ok(
            cascadeTrashService.restoreBatch(loginUser.id, batchId, servletRequest.remoteAddr),
        )
    }

    @GetMapping("/resources")
    override fun search(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) types: Set<AdminResourceType>?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourcePageResponse> {
        val response = resourceService.search(query, types.orEmpty(), page, size)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/resources/{type}/{id}")
    override fun get(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        val response = resourceService.get(type, id)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/resources/{type}/{id}/context")
    override fun getContext(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceContextResponse> {
        val response = resourceContextService.get(type, id)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/resources/PHOTO/{id}/original-access")
    override fun accessOriginalPhoto(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminPhotoAccessRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminPhotoAccessResponse> {
        return ResponseEntity.ok(
            photoAccessService.access(loginUser.id, id, request, servletRequest.remoteAddr),
        )
    }

    @PostMapping("/resources/RETOUCH_REQUEST/{roundId}/items/{retouchPhotoId}/artifacts/{artifactType}/access")
    override fun accessRetouchArtifact(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable roundId: Long,
        @PathVariable retouchPhotoId: Long,
        @PathVariable artifactType: AdminRetouchArtifactType,
        @Valid @RequestBody request: AdminRetouchArtifactAccessRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminRetouchArtifactAccessResponse> {
        return ResponseEntity.ok(
            retouchArtifactAccessService.access(
                loginUser.id,
                roundId,
                retouchPhotoId,
                artifactType,
                request,
                servletRequest.remoteAddr,
            ),
        )
    }

    @PostMapping("/resources/{type}")
    override fun create(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @Valid @RequestBody request: CreateAdminResourceRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        return ResponseEntity.status(HttpStatus.CREATED).body(
            resourceService.create(loginUser.id, type, request, servletRequest.remoteAddr),
        )
    }

    @PatchMapping("/resources/{type}/{id}")
    override fun update(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateAdminResourceRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        return ResponseEntity.ok(
            resourceService.update(loginUser.id, type, id, request, servletRequest.remoteAddr),
        )
    }

    @DeleteMapping("/resources/{type}/{id}")
    override fun delete(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<Unit> {
        if (type in IMMEDIATE_DELETE_TYPES) {
            resourceService.delete(loginUser.id, type, id, request, servletRequest.remoteAddr)
        } else {
            cascadeTrashService.delete(loginUser.id, type, id, request, servletRequest.remoteAddr)
        }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/resources/{type}/{id}/restore")
    override fun restore(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        return ResponseEntity.ok(
            cascadeTrashService.restoreRoot(loginUser.id, type, id, request, servletRequest.remoteAddr),
        )
    }

    @PostMapping("/resources/{type}/{id}/suspend")
    override fun suspend(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        return ResponseEntity.ok(
            suspensionService.suspend(loginUser.id, type, id, request, servletRequest.remoteAddr),
        )
    }

    @PostMapping("/resources/{type}/{id}/activate")
    override fun activate(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: ChangeAdminResourceStateRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminResourceResponse> {
        return ResponseEntity.ok(
            suspensionService.activate(loginUser.id, type, id, request, servletRequest.remoteAddr),
        )
    }

    @PostMapping("/resources/{type}/{id}/reprocess")
    override fun reprocess(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminReprocessRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminReprocessResponse> {
        return ResponseEntity.accepted().body(
            reprocessService.reprocess(loginUser.id, type, id, request, servletRequest.remoteAddr),
        )
    }

    @GetMapping("/resources/GALLERY/{id}/analysis-failures")
    override fun getAnalysisFailures(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable id: Long,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminAnalysisFailuresResponse> {
        val response = reprocessService.getAnalysisFailures(id)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/resources/{type}/{id}/workflows")
    override fun executeWorkflow(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
        @PathVariable type: AdminResourceType,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminWorkflowRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<AdminWorkflowResponse> {
        val response = workflowService.execute(loginUser.id, type, id, request, servletRequest.remoteAddr)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/system-settings")
    override fun getSystemSettings(
        @AuthenticationPrincipal loginUser: AdminLoginUser,
    ): ResponseEntity<AdminSystemSettingsResponse> {
        return ResponseEntity.ok(systemSettingsService.get())
    }
}
