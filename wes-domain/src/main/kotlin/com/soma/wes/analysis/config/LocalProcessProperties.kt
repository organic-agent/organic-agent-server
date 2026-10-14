package com.soma.wes.analysis.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * local 프로필의 대역 어댑터(`LocalAiTaskSender`·`LocalScoreWorkerPool`)가 띄울 스크립트의 자리.
 *
 * prefix가 [AnalysisProperties]와 같아 설정 키는 `app.analysis.local-script-dir` 그대로다. 프로필 블록(application.yml)이 채운다.
 */
@ConfigurationProperties(prefix = "app.analysis")
data class LocalProcessProperties(
    /**
     * Lambda 대신 띄울 대역 스크립트 디렉토리(작업 디렉토리 기준). Lambda 함수 하나가 `<디렉토리>/<함수>.sh` 하나이고
     * 함수 이름은 AI repo 최상위 모듈과 같다. GPU 워커 대역은 이 디렉토리 옆 `../gpu/`에 있다.
     */
    val localScriptDir: String = "",
) {

    val isConfigured: Boolean
        get() = localScriptDir.isNotBlank()
}
