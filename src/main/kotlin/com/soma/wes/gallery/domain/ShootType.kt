package com.soma.wes.gallery.domain

/**
 * 촬영 종류. AI 폴더의 컨셉(1층) 고정 목록을 고르는 키다 — 리허설과 본식은 촬영이
 * 일어나는 환경의 종류가 달라 목록도 다르다(docs/plans/ai-folder-structure.md). 목록 자체는
 * AI repo가 소유하고 이 서버는 종류만 전한다.
 */
enum class ShootType {

    /** 스튜디오·야외 웨딩 촬영. 기존 갤러리는 전부 이것이었다 — 기본값인 이유. */
    REHEARSAL,

    /** 결혼식 당일 스냅. */
    CEREMONY,

    OTHER,
}
