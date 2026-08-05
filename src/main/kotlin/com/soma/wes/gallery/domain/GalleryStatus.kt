package com.soma.wes.gallery.domain

/**
 * 갤러리의 공개 단계. 초대된 사람이 무엇을 할 수 있는지가 이 값으로 갈린다.
 */
enum class GalleryStatus {

    /** 작가가 사진을 올리고 정리하는 중. 초대된 사람에게는 아직 보이지 않는다. */
    DRAFT,

    /** 초대된 사람이 열람하고 사진을 고를 수 있다. */
    OPEN,

    /** 선택이 마감됐다. 열람은 되지만 선택은 바뀌지 않는다. */
    CLOSED,
}
