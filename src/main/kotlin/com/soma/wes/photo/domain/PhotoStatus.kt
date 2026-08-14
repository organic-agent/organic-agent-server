package com.soma.wes.photo.domain

/**
 * 사진 한 장이 업로드 파이프라인의 어디까지 왔는지.
 */
enum class PhotoStatus {

    /** 업로드 URL만 발급된 상태. S3에 객체가 아직 없을 수 있다. */
    PENDING,

    /** 프론트가 업로드 완료를 통보한 상태. 임베딩 대상이 된다. */
    UPLOADED,

    /** 임베딩까지 적재되어 클러스터링 대상이 된 상태. */
    EMBEDDED,
}
