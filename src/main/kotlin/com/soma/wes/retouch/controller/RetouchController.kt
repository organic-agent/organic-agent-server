package com.soma.wes.retouch.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.retouch.controller.docs.RetouchControllerDocs
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.retouch.dto.request.MatchRetouchResultsRequest
import com.soma.wes.retouch.dto.response.MatchRetouchResultsResponse
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.CompleteResultsRequest
import com.soma.wes.retouch.dto.request.IssueResultUploadUrlsRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.response.IssueResultUploadUrlsResponse
import com.soma.wes.retouch.dto.response.RetouchOverviewResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import com.soma.wes.retouch.dto.response.RetouchRoundDetailResponse
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.retouch.service.RetouchRefinementService
import com.soma.wes.retouch.dto.request.RefineRetouchRequest
import com.soma.wes.retouch.dto.response.RefineRetouchResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/retouch")
class RetouchController(
    private val retouchService: RetouchService,
    private val refinementService: RetouchRefinementService,
) : RetouchControllerDocs {

    @GetMapping("/rounds")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.get(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: AddRetouchPhotosRequest,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.addPhotos(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PutMapping("/photos/{photoId}/request")
    override fun updatePhoto(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: UpdateRetouchPhotoRequest,
    ): ResponseEntity<RetouchPhotoResponse> {
        val result = retouchService.updatePhoto(galleryId, photoId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/rounds/{roundNo}")
    override fun getRound(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
    ): ResponseEntity<RetouchRoundDetailResponse> {
        val result = retouchService.getRound(galleryId, roundNo, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/rounds/{roundNo}/results/upload-urls")
    override fun issueResultUploadUrls(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
        @Valid @RequestBody request: IssueResultUploadUrlsRequest,
    ): ResponseEntity<IssueResultUploadUrlsResponse> {
        val result = retouchService.issueResultUploadUrls(galleryId, roundNo, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/rounds/{roundNo}/results/complete")
    override fun completeResults(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
        @Valid @RequestBody request: CompleteResultsRequest,
    ): ResponseEntity<RetouchRoundDetailResponse> {
        val result = retouchService.completeResults(galleryId, roundNo, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/rounds/{roundNo}/send")
    override fun completeRound(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.completeRound(galleryId, roundNo, loginUser.id)

        return ResponseEntity.ok(result)
    }
    @PostMapping("/rounds/{roundNo}/requests")
    override fun submitRequests(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
        @Valid @RequestBody request: SubmitRetouchRequestsRequest,
    ): ResponseEntity<RetouchOverviewResponse> {
        return ResponseEntity.ok(retouchService.submitRequests(galleryId, roundNo, loginUser.id, request))
    }

    @PostMapping("/rounds/{roundNo}/results/match")
    override fun matchResults(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable roundNo: Int,
        @Valid @RequestBody request: MatchRetouchResultsRequest,
    ): ResponseEntity<MatchRetouchResultsResponse> {
        return ResponseEntity.ok(retouchService.matchResults(galleryId, roundNo, loginUser.id, request))
    }

    @PostMapping("/confirm")
    override fun confirm(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<RetouchOverviewResponse> {
        return ResponseEntity.ok(retouchService.confirm(galleryId, loginUser.id))
    }

    @PostMapping("/requests/refine")
    override fun refine(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: RefineRetouchRequest,
    ): ResponseEntity<RefineRetouchResponse> {
        return ResponseEntity.ok(refinementService.refine(galleryId, loginUser.id, request))
    }
}
