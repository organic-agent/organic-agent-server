package com.soma.wes.cluster.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.cluster.controller.docs.PhotoClusterControllerDocs
import com.soma.wes.cluster.dto.response.PhotoClustersResponse
import com.soma.wes.cluster.service.PhotoClusterService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photo-clusters")
class PhotoClusterController(
    private val photoClusterService: PhotoClusterService,
) : PhotoClusterControllerDocs {

    @GetMapping
    override fun cluster(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(required = false) level: Int?,
    ): ResponseEntity<PhotoClustersResponse> {
        val result = photoClusterService.cluster(galleryId, loginUser.id, level)

        return ResponseEntity.ok(result)
    }
}
