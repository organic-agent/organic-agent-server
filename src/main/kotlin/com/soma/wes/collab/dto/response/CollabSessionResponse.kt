package com.soma.wes.collab.dto.response

import com.soma.wes.collab.domain.CollabSession
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "협업 세션. 부부와 담당 작가가 관리 화면에서 본다")
data class CollabSessionResponse(
    val sessionId: Long,
    val galleryId: Long,
    val conceptFolderId: Long,

    @field:Schema(description = "부부가 붙인 이름. 갤러리에 링크가 여러 개라 이것으로 구분한다.", example = "본식 후보")
    val name: String,

    @field:Schema(description = "하객에게 그대로 전달하는 링크. 토큰이 아니라 완성된 URL이다. 폐기된 세션도 이 값을 그대로 보여준다 — 무엇이 끊겼는지 알아야 한다.")
    val collabUrl: String,

    @field:Schema(description = "부부가 링크를 거둬들였는지. 거둬들여도 컨셉 연결과 받은 의견은 남는다.")
    val revoked: Boolean,

    val revokedAt: ZonedDateTime?,

    @field:Schema(description = "하객에게 보여주고 있는 사진 수.")
    val photoCount: Long,

    val createdAt: ZonedDateTime?,
    val expiresAt: ZonedDateTime? = null,
    val coverTitle: String? = null,
    val coverAuthor: String? = null,
    val includeAllAlbums: Boolean = false,
) {

    companion object {
        fun of(session: CollabSession, collabUrl: String, photoCount: Long) = CollabSessionResponse(
            sessionId = session.requiredId,
            galleryId = session.galleryId,
            conceptFolderId = session.conceptFolderId,
            name = session.name,
            collabUrl = collabUrl,
            revoked = session.isRevoked,
            revokedAt = session.revokedAt,
            photoCount = photoCount,
            createdAt = session.createdAt,
            expiresAt = session.expiresAt,
            coverTitle = session.coverTitle,
            coverAuthor = session.coverAuthor,
            includeAllAlbums = session.includeAllAlbums,
        )
    }
}
