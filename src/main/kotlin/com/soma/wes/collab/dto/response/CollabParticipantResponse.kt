package com.soma.wes.collab.dto.response

import com.soma.wes.collab.domain.CollabParticipant
import com.soma.wes.collab.domain.CollabParticipantType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

@Schema(description = "공유폴더에 들어온 사람. 부부가 초대 현황에서 본다")
data class CollabParticipantResponse(
    val participantId: Long,
    val nickname: String,

    @field:Schema(description = "GUEST는 닉네임을 적고 들어온 하객, USER는 반응을 남긴 갤러리 참여자 계정이다.")
    val participantType: CollabParticipantType,

    @field:Schema(
        description = "하객은 닉네임을 적고 들어온 시각, 계정 참여자는 처음 반응을 남긴 시각. " +
            "토큰을 잃고 다시 들어온 하객은 새 사람으로 한 번 더 나온다.",
    )
    val enteredAt: ZonedDateTime?,
) {
    companion object {
        fun from(participant: CollabParticipant) = CollabParticipantResponse(
            participantId = participant.requiredId,
            nickname = participant.nickname,
            participantType = participant.participantType,
            enteredAt = participant.createdAt,
        )
    }
}
