package com.soma.wes.studio.dto.response

import com.soma.wes.studio.domain.Studio
import com.soma.wes.workspace.domain.WorkspaceRole
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "스튜디오")
data class StudioResponse(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val galleryUrl: String,
    @field:Schema(description = "과거 운영 데이터 호환용 필드. 공개 생성 요청에서는 받지 않는다.", deprecated = true)
    val inflowChannel: String?,
    @field:Schema(description = "스튜디오 연락처. 등록하지 않았으면 null이다.", example = "010-1234-5678")
    val contact: String?,
    @field:Schema(description = "스튜디오 소개. 등록하지 않았으면 null이다.", example = "자연광 웨딩 스냅 전문 스튜디오입니다.")
    val description: String?,
    @field:Schema(description = "스튜디오를 만든 시각. 저장할 때 서버가 채운다.", example = "2026-11-02T09:30:00+09:00")
    val createdAt: ZonedDateTime?,
    @field:Schema(description = "요청한 사용자의 이 스튜디오 역할. 소속을 찾지 못하면 null이다.", example = "OWNER")
    val role: WorkspaceRole? = null,
) {

    companion object {
        fun from(studio: Studio, role: WorkspaceRole? = null) = StudioResponse(
            id = studio.workspaceId,
            workspaceId = studio.workspaceId,
            name = studio.name,
            galleryUrl = studio.galleryUrl,
            inflowChannel = studio.inflowChannel,
            contact = studio.contact,
            description = studio.description,
            createdAt = studio.createdAt,
            role = role,
        )
    }
}
