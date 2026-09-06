package com.soma.wes.gallery.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.dto.request.UpdatePersonalGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Personal Gallery")
interface PersonalGalleryControllerDocs {
    @Operation(summary = "결제 완료 후 개인 갤러리 개설", description = "본인 테스트 결제의 이용권을 한 번 사용한다. 개인 갤러리는 즉시 공개 상태이며 플랜 기간을 기본 완료 예정일로 사용한다.")
    fun create(loginUser: LoginUser, request: CreatePersonalGalleryRequest): ResponseEntity<GalleryResponse>
    @Operation(summary = "개인 갤러리 설정 저장", description = "개설자만 이름, 완료 예정일, 목표 장수를 일괄 저장한다. 파트너에게는 허용하지 않는다.")
    fun update(loginUser: LoginUser, galleryId: Long, request: UpdatePersonalGalleryRequest): ResponseEntity<GalleryResponse>
}
