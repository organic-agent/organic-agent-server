package com.soma.wes.retouch.dto


/** DB 트랜잭션을 닫은 뒤 S3와 PDF 작업에 사용하는 불변 값이다. 엔티티나 서명 URL은 포함하지 않는다. */
data class RetouchPdfSnapshotDto(
    val galleryTitle: String,
    /** 회차의 모든 사진에 적용되는 요청. 사진 페이지 앞 "전체 요청" 페이지로 실린다. */
    val roundRequestText: String?,
    val photos: List<RetouchPdfPhotoDto>,
)
