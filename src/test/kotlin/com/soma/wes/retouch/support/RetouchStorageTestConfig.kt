package com.soma.wes.retouch.support

import com.soma.wes.photo.infrastructure.S3PhotoStorage
import com.soma.wes.photo.service.PhotoStorage
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** 서명은 실제 어댑터를 쓰고, 완료 검증의 객체 존재 여부만 테스트가 제어한다. */
@TestConfiguration(proxyBeanMethods = false)
class RetouchStorageTestConfig {
    @Bean
    @Primary
    fun retouchTestStorage(storage: S3PhotoStorage): RetouchTestStorage = RetouchTestStorage(storage)
}

class RetouchTestStorage(storage: S3PhotoStorage) : PhotoStorage by storage {
    val missingKeys: MutableSet<String> = mutableSetOf()
    override fun exists(key: String): Boolean = key !in missingKeys
}
