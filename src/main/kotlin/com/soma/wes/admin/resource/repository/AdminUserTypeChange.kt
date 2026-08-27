package com.soma.wes.admin.resource.repository

import com.soma.wes.admin.resource.dto.AdminResourceResponse

/**
 * 관계 작업이 미확정 사용자의 종류를 확정했을 때 함께 감사할 정확한 행 상태.
 *
 * 두 응답은 사용자 행을 `FOR UPDATE`로 잠근 같은 트랜잭션 안에서 읽는다. 이미 확정된 종류를
 * 그대로 사용한 경우에는 이 값 자체를 만들지 않아 불필요한 USER 리비전을 남기지 않는다.
 */
data class AdminUserTypeChange(
    val before: AdminResourceResponse,
    val after: AdminResourceResponse,
)
