package com.soma.wes.admin.audit.support

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** HTTP 변경 요청의 commit/실패 감사 단계를 filter까지 전달한다. 값 자체는 요청 안에서만 산다. */
object AdminMutationAuditContext {

    const val MUTATION_COMMITTED_ATTRIBUTE = "wes.admin.mutation-committed"
    const val FAILURE_AUDITED_ATTRIBUTE = "wes.admin.failure-audited"

    fun markMutationCommitted() = set(MUTATION_COMMITTED_ATTRIBUTE)

    fun markFailureAudited() = set(FAILURE_AUDITED_ATTRIBUTE)

    private fun set(name: String) {
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request
            ?.setAttribute(name, true)
    }
}
