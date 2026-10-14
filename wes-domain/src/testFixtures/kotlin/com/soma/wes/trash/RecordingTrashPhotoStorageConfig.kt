package com.soma.wes.trash

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class RecordingTrashPhotoStorageConfig {

    @Bean
    @Primary
    fun recordingTrashPhotoStorage(): RecordingTrashPhotoStorage = RecordingTrashPhotoStorage()
}
