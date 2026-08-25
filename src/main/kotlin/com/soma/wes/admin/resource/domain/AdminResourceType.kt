package com.soma.wes.admin.resource.domain

import com.soma.wes.admin.audit.domain.AdminAuditTargetType

/** 현재 제품에 실제로 존재하는 최고 관리자 관리 단위. */
enum class AdminResourceType(
    val auditTargetType: AdminAuditTargetType,
) {
    USER(AdminAuditTargetType.USER),
    STUDIO(AdminAuditTargetType.STUDIO),
    GALLERY(AdminAuditTargetType.GALLERY),
    PHOTO(AdminAuditTargetType.PHOTO),
    SELECTION(AdminAuditTargetType.SELECTION),
    COLLABORATION(AdminAuditTargetType.COLLABORATION),
    /** 아직 별도 album 테이블이 없어 현재의 사진 폴더 그룹을 앨범 관리 단위로 노출한다. */
    ALBUM(AdminAuditTargetType.ALBUM),
    /** 보정 항목을 묶는 회차가 요청의 상태·생명주기를 소유한다. */
    RETOUCH_REQUEST(AdminAuditTargetType.RETOUCH_REQUEST),
}
