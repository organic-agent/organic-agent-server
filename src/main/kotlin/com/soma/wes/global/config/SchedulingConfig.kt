package com.soma.wes.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@Scheduled`를 켠다.
 *
 * 지금 도는 일은 [com.soma.wes.auth.support.OAuthStateCleaner] 하나뿐이다. 인스턴스마다
 * 독립으로 돌므로, 여러 대로 늘리면 같은 작업이 중복 실행된다는 점을 전제로 작업을 짜야
 * 한다(만료 행 DELETE는 중복돼도 결과가 같아서 지금은 문제되지 않는다).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
