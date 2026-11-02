package com.soma.wes.admin.resource.domain

/** 현재 제품에 실제로 존재하는 최고 관리자 관리 단위. */
enum class AdminResourceType {
    USER,
    WORKSPACE,
    STUDIO,
    GALLERY,
    PHOTO,
    CONCEPT_FOLDER,
    DETAIL_FOLDER,
    /** photo_id가 곧 관리 리소스 식별자다. 사진당 활성 배정은 최대 한 건이다. */
    PHOTO_CATEGORY_ASSIGNMENT,
    PHOTO_RATING,
    SELECTION,
    COLLABORATION,
    /** 보정 항목을 묶는 회차가 요청의 상태·생명주기를 소유한다. */
    RETOUCH_REQUEST,
}
