package com.soma.wes.admin.resource.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 관리자 비동기 작업 실행기의 재시도·외부 알림 경계. 비밀 토큰은 Parameter Store에서만 주입한다. */
@ConfigurationProperties("app.admin.workflow-executor")
data class AdminWorkflowExecutorProperties(
    val enabled: Boolean = true,
    val batchSize: Int = 20,
    val maxAttempts: Int = 5,
    val retryDelay: Duration = Duration.ofMinutes(1),
    val staleTimeout: Duration = Duration.ofMinutes(10),
    /** 운영 Lambda event age 20m + 함수 timeout 15m + 종료 반영 여유 5m 뒤에만 결과 불명을 회수한다. */
    val dispatchedTimeout: Duration = Duration.ofMinutes(40),
    val fixedDelay: Duration = Duration.ofSeconds(10),
)
