package com.soma.wes.folder.dto

// [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner 안에 중첩돼 있던 data class를 dto 루트로 옮겼다 (AiFolderPlanner.MemberPhoto → MemberPhotoDto).
/**
 * 폴더 계획에 들어갈 사진 한 장의 분석 요약. 벡터 없이 그룹·피사체·연사만 담는다.
 * [embedGroupId]가 null이면(분석 미완) "기타"로, [subjects]는 세부 폴더의 컷 종류 다수결에 쓰인다.
 */
data class MemberPhotoDto(
    val photoId: Long,
    val embedGroupId: Int?,
    val subjects: String?,
    // [GLOSSARY-1 2026-09-27] clusterId → burstId (용어집: 연사)
    val burstId: Int?,
)
