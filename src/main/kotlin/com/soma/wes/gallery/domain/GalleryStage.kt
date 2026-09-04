package com.soma.wes.gallery.domain

/**
 * 갤러리 화면의 6단계 진행 상태.
 *
 * [GalleryStatus]는 초대받은 사용자의 공개/선택 가능 여부를 나타내고, 이 값은 작가 화면의
 * 스테퍼와 목록 필터를 위한 제품 진행 상태다. 서로 다른 질문에 답하므로 하나로 합치지 않는다.
 */
enum class GalleryStage {
    UPLOAD,
    SELECTION_IN_PROGRESS,
    SELECTION_COMPLETED,
    RETOUCH,
    DELIVERY,
    ARCHIVED,
}
