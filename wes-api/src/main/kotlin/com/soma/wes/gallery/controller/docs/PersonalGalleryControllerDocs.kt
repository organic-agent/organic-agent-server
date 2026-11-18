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
    @Operation(summary = "무료 또는 프로 개인 갤러리 개설", description = "무료는 계정당 한 번, 생성일부터 달력 기준 한 달·500장이다. 프로는 본인의 미사용 쿠폰 한 장을 사용해 생성일부터 달력 기준 1년·10,000장을 제공한다. 쿠폰은 새 갤러리에만 사용할 수 있으며 기존 무료 갤러리 업그레이드는 지원하지 않는다. 개인 갤러리는 즉시 공개되며 이용권 만료일을 기본 목표일로 사용한다. 목표일이 이용 기간 마지막 날을 넘기면 400 GALLERY_400_9다.")
    fun create(loginUser: LoginUser, request: CreatePersonalGalleryRequest): ResponseEntity<GalleryResponse>
    @Operation(summary = "개인 갤러리 설정 저장", description = "개설자와 파트너가 이름, 목표일, 목표 장수를 일괄 저장한다. 목표일은 날짜 기준으로 이용 기간 마지막 날까지 정할 수 있고, 넘기면 400 GALLERY_400_9다. 지난 날짜는 400 GALLERY_400_2다.")
    fun update(loginUser: LoginUser, galleryId: Long, request: UpdatePersonalGalleryRequest): ResponseEntity<GalleryResponse>
    @Operation(
        summary = "심사용 갤러리 작업 초기화",
        description = "심사용 링크 계정만 쓸 수 있다(그 밖의 사용자는 403 GALLERY_403_7). 올린 사진과 AI 분석은 남기고 " +
            "셀렉·별점·메모·댓글·거르기·추천·보정 요청·앨범·협업 링크·폴더 수정·활동 기록을 지운 뒤, AI 폴더를 처음 상태로 다시 만든다. " +
            "갤러리 단계는 사진 올리기 직후로 돌아간다. 되돌릴 수 없다.",
    )
    fun reset(loginUser: LoginUser, galleryId: Long): ResponseEntity<GalleryResponse>
}
