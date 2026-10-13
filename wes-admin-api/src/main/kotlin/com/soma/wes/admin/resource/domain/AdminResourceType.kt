package com.soma.wes.admin.resource.domain

import com.soma.wes.admin.audit.domain.AdminAuditTargetType

/** 현재 제품에 실제로 존재하는 최고 관리자 관리 단위. */
enum class AdminResourceType(
    val auditTargetType: AdminAuditTargetType,
) {
    USER(AdminAuditTargetType.USER),
    WORKSPACE(AdminAuditTargetType.WORKSPACE),
    STUDIO(AdminAuditTargetType.STUDIO),
    GALLERY(AdminAuditTargetType.GALLERY),
    PHOTO(AdminAuditTargetType.PHOTO),
    CONCEPT_FOLDER(AdminAuditTargetType.CONCEPT_FOLDER),
    DETAIL_FOLDER(AdminAuditTargetType.DETAIL_FOLDER),
    /** photo_id가 곧 관리 리소스 식별자다. 사진당 활성 배정은 최대 한 건이다. */
    PHOTO_CATEGORY_ASSIGNMENT(AdminAuditTargetType.PHOTO_CATEGORY_ASSIGNMENT),
    PHOTO_RATING(AdminAuditTargetType.PHOTO_RATING),
    SELECTION(AdminAuditTargetType.SELECTION),
    COLLABORATION(AdminAuditTargetType.COLLABORATION),
    /** 보정 항목을 묶는 회차가 요청의 상태·생명주기를 소유한다. */
    RETOUCH_REQUEST(AdminAuditTargetType.RETOUCH_REQUEST),
}
