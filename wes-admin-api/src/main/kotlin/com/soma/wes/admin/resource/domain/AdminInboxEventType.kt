package com.soma.wes.admin.resource.domain

/**
 * 관리자 인박스에는 서버가 정한 작업 유형만 들어간다. 자유 입력 제목/본문을 받지 않아
 * 사용자 이름, 연락처, 메시지 원문이 장기 운영 데이터로 복제되는 경로를 닫는다.
 */
enum class AdminInboxEventType(private val description: String) {
    PHOTO_REPLACED("사진 교체 후속 확인"),
    CUSTOMER_FOLLOW_UP("고객 후속 조치 확인"),
    PROCESSING_REVIEW("처리 작업 상태 확인"),
    CONTENT_REVIEW("콘텐츠 검토"),
    DELIVERY_REVIEW("결과 전달 상태 확인"),
    DATA_CORRECTION_REVIEW("데이터 정정 결과 확인"),
    GENERAL_OPERATION_NOTICE("운영 항목 확인"),
    ;

    fun safeSummary(targetType: AdminResourceType, targetId: Long): String =
        "${targetType.displayName} #$targetId · $description"

    private val AdminResourceType.displayName: String
        get() = when (this) {
            AdminResourceType.USER -> "사용자"
            AdminResourceType.WORKSPACE -> "작업공간"
            AdminResourceType.STUDIO -> "스튜디오"
            AdminResourceType.GALLERY -> "갤러리"
            AdminResourceType.PHOTO -> "사진"
            AdminResourceType.CONCEPT_FOLDER -> "컨셉 폴더"
            AdminResourceType.DETAIL_FOLDER -> "세부 폴더"
            AdminResourceType.PHOTO_CATEGORY_ASSIGNMENT -> "사진 카테고리 배정"
            AdminResourceType.PHOTO_RATING -> "사진 별점"
            AdminResourceType.SELECTION -> "셀렉"
            AdminResourceType.COLLABORATION -> "협업"
            AdminResourceType.RETOUCH_REQUEST -> "보정 요청"
        }
}
