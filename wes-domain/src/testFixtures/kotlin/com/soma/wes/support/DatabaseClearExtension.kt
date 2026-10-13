package com.soma.wes.support

import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * 각 테스트 메서드 전에 [DatabaseCleaner]를 부른다. [IntegrationTest]가 등록하므로
 * 테스트 클래스가 직접 붙일 일은 없다.
 */
class DatabaseClearExtension : BeforeEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        SpringExtension.getApplicationContext(context)
            .getBean(DatabaseCleaner::class.java)
            .clear()
    }
}
