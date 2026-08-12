package com.soma.wes.collab.dto

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.gallery.domain.Gallery

/**
 * 협업 링크로 들어온 요청이 통과한 결과. [com.soma.wes.collab.support.CollabSessionAccess]가 만든다.
 *
 * 세션과 갤러리를 함께 들고 다니는 이유는 둘을 따로 읽으면 확인한 것과 쓰는 것이 어긋나기
 * 때문이다 — 세션만 넘기면 호출부가 갤러리를 다시 읽어야 하고, 그때는 이미 "이 갤러리를
 * 보여줘도 되는가"를 판단한 시점이 아니다.
 */
data class CollabAccessDto(
    val session: CollabSession,
    val gallery: Gallery,
) {

    val sessionId: Long
        get() = session.requiredId
}