package com.soma.wes.support

import com.soma.wes.analysis.support.AnalysisJobEventListener
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * 각 테스트 메서드 전에 [DatabaseCleaner]를 부른다. [IntegrationTest]가 등록하므로
 * 테스트 클래스가 직접 붙일 일은 없다.
 *
 * 지우기 전에 앞 테스트가 넘긴 분석 잡 이력이 다 쓰이기를 기다린다 — 이력은 이력 스레드가 늦게 쓰므로(#346),
 * 기다리지 않으면 지운 뒤에 앞 테스트의 이력이 도착한다.
 */
class DatabaseClearExtension : BeforeEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        val applicationContext = SpringExtension.getApplicationContext(context)
        applicationContext.getBeanProvider(AnalysisJobEventListener::class.java).ifAvailable { it.awaitIdle() }
        applicationContext.getBean(DatabaseCleaner::class.java).clear()
    }
}
