package com.soma.wes.studio.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.studio.controller.docs.StudioControllerDocs
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.service.StudioService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/studios")
class StudioController(
    private val studioService: StudioService,
) : StudioControllerDocs {

    /**
     * 스튜디오를 만드는 것이 곧 작가 온보딩의 마지막 단계다. 사용자 종류도 여기서 확정된다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: CreateStudioRequest,
    ): StudioResponse = StudioResponse.from(
        studioService.create(loginUser.id, request.name, request.galleryUrl, request.inflowChannel),
    )

    /**
     * 주소를 경로가 아니라 쿼리로 받는다. 사용자가 입력 중인 값이 그대로 들어오므로 경로에
     * 두면 슬래시 같은 문자에서 라우팅이 어긋나고, 형식 오류가 404로 뭉개진다.
     */
    @GetMapping("/gallery-url/availability")
    override fun checkGalleryUrl(
        @RequestParam galleryUrl: String,
    ): GalleryUrlAvailabilityResponse = GalleryUrlAvailabilityResponse(
        galleryUrl = galleryUrl,
        available = studioService.isGalleryUrlAvailable(galleryUrl),
    )

    @GetMapping("/me")
    override fun getMine(@AuthenticationPrincipal loginUser: LoginUser): StudioResponse =
        StudioResponse.from(studioService.getMine(loginUser.id))

    @PatchMapping("/me")
    override fun updateMine(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: UpdateStudioRequest,
    ): StudioResponse = StudioResponse.from(
        studioService.updateMine(loginUser.id, request.name, request.galleryUrl),
    )
}
