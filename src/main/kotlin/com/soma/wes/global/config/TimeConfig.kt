package com.soma.wes.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit.MICROS
import java.util.Optional

/**
 * 시각을 다루는 공통 설정.
 *
 * 저장·연산은 UTC로 하고, 한국 시간은 사람에게 보여줄 때와 날짜 경계를 따질 때만 쓴다.
 * 시각은 절대적인 순간이고 "한국 시간 오후 3시"는 그 순간을 표시하는 방식일 뿐이다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class TimeConfig {

    companion object {
        /** 날짜 경계(예: "오늘"의 시작과 끝)를 따질 때 쓴다. 저장 형식이 아니다. */
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    /**
     * 시각의 단일 출처. 코드 어디서도 `now()`를 직접 부르지 않고 이 빈을 통하면,
     * 테스트에서 `Clock.fixed(...)`로 갈아끼워 시간을 고정할 수 있다.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * JPA 감사(@CreatedDate/@LastModifiedDate)가 쓸 시각.
     * Postgres가 보존하는 정밀도(마이크로초)에 맞춰 잘라, 저장 전후 값이 달라지지 않게 한다.
     */
    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider =
        DateTimeProvider { Optional.of(ZonedDateTime.now(clock).truncatedTo(MICROS)) }
}
