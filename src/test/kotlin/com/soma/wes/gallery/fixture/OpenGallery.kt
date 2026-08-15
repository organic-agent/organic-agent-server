package com.soma.wes.gallery.fixture

import com.soma.wes.user.domain.User

/**
 * 통합 테스트 대부분이 출발점으로 쓰는 조합 — 열린 갤러리와 그 양쪽 사람.
 * 관리 경로는 [galleryId]로, 인증은 두 [User]로 요청을 만든다.
 */
data class OpenGallery(
    val photographer: User,
    val member: User,
    val galleryId: Long,
)
