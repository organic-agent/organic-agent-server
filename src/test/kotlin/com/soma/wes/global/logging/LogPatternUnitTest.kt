package com.soma.wes.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.LoggingEvent
import javax.xml.parsers.DocumentBuilderFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

/**
 * 운영 로그 줄의 모양을 지키는 회귀 가드 — `logback-spring.xml`의 `LOG_PATTERN`이 [LogContext]의 키를 찍고, 값이 없는 키는 줄에서 뺀다.
 * Loki 가 줄을 `| logfmt`로 읽으므로 키 이름과 "빈 키가 붙지 않는다"가 계약이다.
 *
 * 그 패턴은 prod 프로필의 파일 appender 만 쓴다(local·test 콘솔은 스프링 부트 기본 패턴). 그래서 출력을 잡는 대신
 * 설정 파일에서 패턴 문자열을 읽어 logback 의 레이아웃에 직접 먹인다.
 */
class LogPatternUnitTest {

    private val context = LoggerContext()
    private val layout = PatternLayout().apply {
        context = this@LogPatternUnitTest.context
        pattern = readLogPattern()
        start()
    }

    @Test
    fun `갤러리와 잡과 업로드 세션과 사용자는 실려 있을 때만 줄에 찍힌다`() {
        // when
        val full = format(
            mapOf(
                LogContext.TRACE_ID to "sweep-0123456789ab",
                LogContext.GALLERY_ID to "7",
                LogContext.JOB_ID to "3",
                LogContext.UPLOAD_SESSION_ID to "3f0c1a52-7c1e",
                LogContext.USER_ID to "12",
            ),
        )
        val partial = format(mapOf(LogContext.TRACE_ID to "0123456789abcdef", LogContext.GALLERY_ID to "7"))
        val empty = format(emptyMap())

        // then
        assertSoftly { softly ->
            softly.assertThat(full)
                .contains(" traceId=sweep-0123456789ab galleryId=7 jobId=3 uploadSessionId=3f0c1a52-7c1e userId=12 logger=")
            softly.assertThat(partial).contains(" traceId=0123456789abcdef galleryId=7 logger=")
            softly.assertThat(empty).contains(" traceId=null logger=")
            softly.assertThat(empty).doesNotContain("galleryId=", "jobId=", "uploadSessionId=", "userId=")
        }
    }

    @Test
    fun `메시지는 맨 뒤에 그대로 붙어 event 로 거를 수 있다`() {
        // when
        val line = format(mapOf(LogContext.GALLERY_ID to "7"))

        // then
        assertThat(line.trimEnd()).endsWith("${javaClass.simpleName} event=probe gallery=7")
    }

    private fun format(mdc: Map<String, String>): String {
        val event = LoggingEvent(javaClass.name, context.getLogger(javaClass), Level.INFO, "event=probe gallery=7", null, null)
        event.setMDCPropertyMap(mdc)
        return layout.doLayout(event)
    }

    private fun readLogPattern(): String {
        val config = checkNotNull(javaClass.getResourceAsStream("/logback-spring.xml")) { "logback-spring.xml 이 클래스패스에 없다" }
        val properties = config.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }.getElementsByTagName("property")
        return (0 until properties.length)
            .map { properties.item(it).attributes }
            .single { it.getNamedItem("name").nodeValue == "LOG_PATTERN" }
            .getNamedItem("value").nodeValue
    }
}
