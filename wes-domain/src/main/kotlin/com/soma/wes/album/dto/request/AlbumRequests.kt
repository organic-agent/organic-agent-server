package com.soma.wes.album.dto.request

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.soma.wes.album.domain.AlbumShareMode
import com.soma.wes.album.dto.AlbumLayoutDto
import io.swagger.v3.oas.annotations.media.Schema

data class SaveAlbumRequest(
    @field:Schema(description = "마지막 조회 또는 저장의 버전. 스튜디오가 처음 만들 때는 0.", example = "0")
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long,
    @field:Schema(description = "편집기에서 읽고 쓰는 전체 앨범 문서")
    val album: AlbumLayoutDto,
)

data class SendAlbumRequest(
    @field:Schema(description = "발송할 초안의 버전", example = "1")
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long,
    @field:Schema(description = "view는 발송 즉시 확정, edit는 고객용 사본을 열어 준다.", example = "edit")
    val mode: AlbumShareMode,
)

data class ReturnAlbumRequest(
    @field:Schema(description = "돌려보낼 고객 사본의 버전", example = "3")
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long,
)

data class ConfirmAlbumRequest(
    @field:Schema(description = "검토한 앨범의 버전", example = "4")
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    val expectedRevision: Long,
    @field:Schema(description = "기본값 true는 고객이 돌려보낸 사본으로, false는 현재 스튜디오 초안으로 확정한다.", example = "true")
    @JsonSetter(nulls = Nulls.FAIL)
    val useReturned: Boolean = true,
)
