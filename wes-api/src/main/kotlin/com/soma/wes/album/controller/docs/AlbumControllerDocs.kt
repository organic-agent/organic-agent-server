package com.soma.wes.album.controller.docs

import com.soma.wes.album.dto.request.ConfirmAlbumRequest
import com.soma.wes.album.dto.request.ReturnAlbumRequest
import com.soma.wes.album.dto.request.SaveAlbumRequest
import com.soma.wes.album.dto.request.SendAlbumRequest
import com.soma.wes.album.dto.response.AlbumPhotosResponse
import com.soma.wes.album.dto.response.AlbumResponse
import com.soma.wes.auth.domain.LoginUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "Album", description = "스튜디오 앨범 구성")
interface AlbumControllerDocs {
    @Operation(
        summary = "앨범 읽기",
        description = "스튜디오는 초안부터, 초대 고객은 발송 이후부터 읽는다. 고객에게는 고객용 사본 또는 확정본이 반환된다.",
    )
    @ApiResponse(
        responseCode = "200",
        content = [Content(
            mediaType = "application/json",
            schema = Schema(implementation = AlbumResponse::class),
            examples = [ExampleObject(value = """
                {
                  "status":"DRAFT", "revision":1, "updatedAt":"2026-11-11T16:35:30+09:00",
                  "sentAt":null, "shareMode":null, "sentAlbum":null, "returnedAlbum":null, "changedFrameIds":[],
                  "album":{
                    "page":{"w":305,"h":305,"name":"12 × 12"},
                    "spreads":[{"id":"sp1","frames":[{"id":"fr1","p":0,"x":0.08,"y":0.08,"w":0.84,"h":0.84}],"deco":null}],
                    "guides":[{"id":"gd1","dir":"v","pos":1.5,"scope":"all"}],
                    "assign":{"fr1":1234},"crop":{"fr1":{"pid":1234,"s":1.4,"px":0.3,"py":0.5}},"gone":{}
                  }
                }
            """)],
        )],
    )
    @ApiResponse(responseCode = "403", description = "앨범 참여 권한이 없는 사용자")
    @ApiResponse(responseCode = "404", description = "아직 읽을 수 있는 앨범이 없음")
    fun get(loginUser: LoginUser, galleryId: Long): ResponseEntity<AlbumResponse>

    @Operation(
        summary = "선택 사진별 최신 보정본 주소 조회",
        description = "완료되어 발송된 회차만 조회한다. 최신 회차에서 다루지 않은 사진은 이전 완료 회차의 결과를 돌려준다.",
    )
    fun listPhotos(loginUser: LoginUser, galleryId: Long): ResponseEntity<AlbumPhotosResponse>

    @Operation(
        summary = "스튜디오 초안 생성·저장",
        description = "첫 저장은 expectedRevision=0으로 보낸다. 이후 현재 버전과 다르면 409가 반환된다. " +
            "생성 뒤 페이지 규격은 고정된다. 저장은 고객에게 보낸 앨범이나 고객 사본에 영향을 주지 않는다.",
    )
    @ApiResponse(responseCode = "409", description = "버전이 바뀌었거나 저장할 수 없는 규격·단계")
    fun saveStudio(loginUser: LoginUser, galleryId: Long, request: SaveAlbumRequest): ResponseEntity<AlbumResponse>

    @Operation(
        summary = "고객에게 앨범 발송",
        description = "보정 최종 확정 뒤 선택 사진이 모든 프레임을 정확히 채웠을 때 보낼 수 있다. " +
            "view는 즉시 앨범을 확정하고 갤러리를 보관한다. edit는 고객 사본을 열며 재발송하면 이전 사본을 대체한다.",
    )
    @ApiResponse(responseCode = "409", description = "버전 충돌, 보정 미확정, 또는 프레임이 채워지지 않음")
    fun send(loginUser: LoginUser, galleryId: Long, request: SendAlbumRequest): ResponseEntity<AlbumResponse>

    @Operation(
        summary = "고객 사본 저장",
        description = "edit로 받은 고객만 최신 발송본의 사진 배치와 크롭을 수정할 수 있다. 반환된 버전을 다음 저장에 사용한다.",
    )
    @ApiResponse(responseCode = "409", description = "버전 충돌, 수정 가능 단계가 아님, 또는 발송본 레이아웃 변경")
    fun saveClient(loginUser: LoginUser, galleryId: Long, request: SaveAlbumRequest): ResponseEntity<AlbumResponse>

    @Operation(
        summary = "스튜디오에 앨범 돌려보내기",
        description = "edit로 받은 고객이 모든 프레임을 채운 사본을 제출한다. 스튜디오 조회에서 변경된 프레임을 확인할 수 있다.",
    )
    @ApiResponse(responseCode = "409", description = "버전 충돌, 수정 가능 단계가 아님, 또는 프레임이 채워지지 않음")
    fun returnToStudio(loginUser: LoginUser, galleryId: Long, request: ReturnAlbumRequest): ResponseEntity<AlbumResponse>

    @Operation(
        summary = "스튜디오 앨범 최종 확정",
        description = "고객이 돌려보낸 사본 또는 스튜디오 초안을 선택해 확정한다. 확정본은 양쪽에 공개되고 갤러리는 보관 상태가 된다.",
    )
    @ApiResponse(responseCode = "409", description = "버전 충돌, 고객이 아직 돌려보내지 않음, 또는 프레임이 채워지지 않음")
    fun confirm(loginUser: LoginUser, galleryId: Long, request: ConfirmAlbumRequest): ResponseEntity<AlbumResponse>
}
