package com.soma.wes.support

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/**
 * 한 클래스의 로거가 남긴 줄을 모은다. 로그가 계약인 자리(운영 알림이 읽는 `event=` 이름·필드, MDC 키)를 단언할 때 쓴다.
 * `use { }`로 감싸 끝나면 떼어 낸다 — 붙여 둔 채로 두면 같은 컨텍스트의 다음 테스트 줄까지 쌓인다.
 */
class CapturedLogs(target: KClass<*>) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(target.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    /** `event=<name>`으로 시작하는 줄. */
    fun eventsOf(name: String): List<ILoggingEvent> =
        appender.list.toList().filter { it.formattedMessage.startsWith("event=$name ") }

    override fun close() {
        logger.detachAppender(appender)
    }
}
