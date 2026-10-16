package com.soma.wes.agent.dto

import com.soma.wes.agent.repository.projection.AgentPhotoRow

/**
 * 판단 전에 한 번 읽어 둔 갤러리. 판단(Bedrock, 약 1.5초)은 DB 커넥션을 놓은 채 기다리고,
 * 답은 이 스냅숏으로 만든다. 실행할 때는 다시 읽어 그사이 바뀐 것을 반영한다.
 */
data class AgentGallerySnapshotDto(
    val galleryId: Long,
    val maxSelectablePhotoCount: Int?,
    /** 선택 앨범이 아직 제출되지 않았는가. 제출 뒤에는 실행 계획을 만들지 않는다. */
    val selectionEditable: Boolean,
    val concepts: List<Concept>,
    val photos: List<AgentPhotoRow>,
) {

    data class Concept(
        val id: Long,
        val name: String,
        val details: List<Detail>,
    )

    data class Detail(
        val id: Long,
        val name: String,
    )
}
