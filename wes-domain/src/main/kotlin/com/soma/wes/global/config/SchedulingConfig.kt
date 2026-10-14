package com.soma.wes.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled`를 켠다.
 *
 * 스레드 수는 `spring.task.scheduling.pool.size`(wes-api는 `application-api-runtime.yml`의 3)다 — 서로 다른 작업은 동시에 돌 수 있고,
 * 같은 작업은 `fixedDelay`·cron이라 자기 자신과 겹치지 않는다. 작업은 인스턴스마다 독립으로 돌므로, 여러 대로 늘리면 같은 작업이
 * 중복 실행된다는 점을 전제로 짜야 한다(선점·`SKIP LOCKED`·만료 행 삭제처럼 겹쳐도 결과가 같게).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
