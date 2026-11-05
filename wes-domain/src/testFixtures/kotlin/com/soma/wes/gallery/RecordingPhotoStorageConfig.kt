package com.soma.wes.gallery

import java.time.Clock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class RecordingPhotoStorageConfig {

    @Bean
    @Primary
    fun recordingPhotoStorage(clock: Clock): RecordingPhotoStorage = RecordingPhotoStorage(clock)
}
