package com.soma.wes.collab.dto.response

import com.soma.wes.collab.domain.CollabSession
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "공유폴더. 갤러리 참여자가 관리 화면에서 본다")
data class CollabSessionResponse(
    val sessionId: Long,
    val galleryId: Long,

    @field:Schema(description = "부부가 붙인 이름. 갤러리에 링크가 여러 개라 이것으로 구분한다.", example = "본식 후보")
    val name: String,

    @field:Schema(description = "하객에게 그대로 전달하는 링크. 토큰이 아니라 완성된 URL이다. 폐기된 세션도 이 값을 그대로 보여준다 — 무엇이 끊겼는지 알아야 한다.")
    val collabUrl: String,

    @field:Schema(description = "부부가 링크를 거둬들였는지. 거둬들여도 사진 구성과 받은 의견은 남는다.")
    val revoked: Boolean,

    @field:Schema(description = "부부가 링크를 거둬들인 시각. 살아 있는 링크면 null이고, 다시 발행하면 null로 돌아간다.", example = "2026-11-05T09:00:00+09:00")
    val revokedAt: ZonedDateTime?,

    @field:Schema(description = "하객에게 보여주고 있는 사진 수.")
    val photoCount: Long,

    @field:Schema(description = "닉네임을 적고 들어온 하객과 반응을 남긴 참여자 계정 수. 보기만 한 사람은 세지 않는다.")
    val participantCount: Long,

    @field:Schema(description = "공유폴더를 연 시각. 저장된 폴더면 늘 채워진다.", example = "2026-11-04T10:15:30+09:00")
    val createdAt: ZonedDateTime?,
    @field:Schema(
        description = "링크 만료 시각. 공유폴더를 열거나 다시 발행한 때부터 7일이고, 지나면 하객이 열 수 없다. null이면 기한이 없다.",
        example = "2026-11-11T10:15:30+09:00",
    )
    val expiresAt: ZonedDateTime? = null,
    @field:Schema(description = "부부가 적은 표지 제목. 적지 않았으면 null이고, 그때 하객 화면은 name을 제목으로 쓴다.", example = "민지 ♥ 준호의 본식")
    val coverTitle: String? = null,
    @field:Schema(description = "부부가 적은 표지 작성자 표기. 적지 않았으면 null이다.", example = "민지·준호")
    val coverAuthor: String? = null,
    val includeAllAlbums: Boolean = false,
) {

    companion object {
        fun of(session: CollabSession, collabUrl: String, photoCount: Long, participantCount: Long) = CollabSessionResponse(
            sessionId = session.requiredId,
            galleryId = session.galleryId,
            name = session.name,
            collabUrl = collabUrl,
            revoked = session.isRevoked,
            revokedAt = session.revokedAt,
            photoCount = photoCount,
            participantCount = participantCount,
            createdAt = session.createdAt,
            expiresAt = session.expiresAt,
            coverTitle = session.coverTitle,
            coverAuthor = session.coverAuthor,
            includeAllAlbums = session.includeAllAlbums,
        )
    }
}
