package com.soma.wes.admin.audit.domain

enum class AdminAuditTargetType {
    AUTHENTICATION,
    ADMIN_ACCOUNT,
    USER,
    WORKSPACE,
    STUDIO,
    GALLERY,
    PHOTO,
    CONCEPT_FOLDER,
    DETAIL_FOLDER,
    PHOTO_CATEGORY_ASSIGNMENT,
    /** 리소스·테이블은 V17에서 지웠다. 옛 감사 로그 행을 읽기 위해 값만 남긴다. */
    CATEGORIZATION_JOB,
    PHOTO_RATING,
    SELECTION,
    COLLABORATION,
    RETOUCH_REQUEST,
    SYSTEM_SETTING,
    TRASH_BATCH,
    IMPERSONATION,
    ADMIN_OPERATION,
}
