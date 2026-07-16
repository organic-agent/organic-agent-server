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
 * 서버 애플리케이션과 DB 시간대를 KST(Asia/Seoul)로 고정한다.
 * 시각의 생성·저장·표시를 모두 KST 기준으로 통일해, 로그와 조회 결과가 +09:00으로 일관된다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class TimeConfig {

    companion object {
        /** 서버·DB 공통 기준 시간대. 시각 생성과 날짜 경계 판단 모두 이 존을 따른다. */
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    /**
     * 시각의 단일 출처. 코드 어디서도 `now()`를 직접 부르지 않고 이 빈을 통하면,
     * 테스트에서 `Clock.fixed(...)`로 갈아끼워 시간을 고정할 수 있다.
     */
    @Bean
    fun clock(): Clock = Clock.system(KST)

    /**
     * JPA 감사(@CreatedDate/@LastModifiedDate)가 쓸 시각.
     * Postgres가 보존하는 정밀도(마이크로초)에 맞춰 잘라, 저장 전후 값이 달라지지 않게 한다.
     */
    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider =
        DateTimeProvider { Optional.of(ZonedDateTime.now(clock).truncatedTo(MICROS)) }
}
