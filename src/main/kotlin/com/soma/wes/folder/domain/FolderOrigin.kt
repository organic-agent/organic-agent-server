package com.soma.wes.folder.domain

/**
 * 부모폴더를 만든 주체. 세트 단위 표시·삭제를 위해 남긴다 — AI가 만든 세트를 서버가 지우거나
 * 덮어쓰는 일은 없고, 사용자가 세트째 지우는 화면의 판별 값이다.
 */
enum class FolderOrigin {

    /** 사용자가 직접 만든 폴더. */
    MANUAL,

    /** `POST /folder-groups/ai`가 컨셉 배정으로 만든 폴더. `analysis_job_id`가 세트 키다. */
    AI,
}
