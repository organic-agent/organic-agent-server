package com.soma.wes.photo.domain

/**
 * S3에 원본이 있는가 — 사진 상태가 답하는 것은 이것 하나다.
 *
 * 임베딩·점수·백분위처럼 분석이 어디까지 왔는지는 [PhotoAnalysis]의 컬럼 유무가 말한다. 같은 사실을
 * 두 곳에 두면 어긋난다(옛 `EMBEDDED`는 `preview_key`·`embedding`과 늘 같은 것을 뜻했다).
 */
enum class PhotoStatus {

    /** 업로드 URL만 발급된 상태. S3에 객체가 아직 없을 수 있어 조회 URL을 주지 않는다. */
    PENDING,

    /** 원본이 S3에 있다. 프론트의 완료 통보 또는 서버의 HeadObject 보정이 옮긴다. 임베딩 대상이 된다. */
    UPLOADED,
}
