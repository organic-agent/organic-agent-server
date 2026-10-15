package com.soma.wes.global.cluster

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 리더 임대가 꺼져 있으면(서버 한 대·로컬·테스트) 이 서버가 늘 리더다 — 오늘과 같은 동작. */
@Component
@ConditionalOnProperty(prefix = "app.cluster.leader", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class StandaloneSchedulerLeadership : SchedulerLeadership {
    override fun isLeader(): Boolean = true
}
