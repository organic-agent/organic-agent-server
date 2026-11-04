package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.GalleryInviteKind
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.ZonedDateTime

data class IssueGalleryInviteRequest(
    @field:Schema(description = "초대가 만드는 소속 종류")
    val kind: GalleryInviteKind = GalleryInviteKind.GALLERY_MEMBER,

    @field:Min(1)
    @field:Max(100)
    @field:Schema(
        description = "이 링크로 들어올 수 있는 사람 수. 1~100이며 생략하면 2명이다. 갤러리 정원이 차면 남은 횟수와 상관없이 수락을 거절한다.",
        example = "2",
    )
    val maxUses: Int = 2,

    @field:Schema(description = "만료 시각. 생략하면 발급 시점부터 7일")
    val expiresAt: ZonedDateTime? = null,
)
