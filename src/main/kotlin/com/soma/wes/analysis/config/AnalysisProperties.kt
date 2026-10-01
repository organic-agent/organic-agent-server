package com.soma.wes.analysis.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 분석 파이프라인 설정 — 임베더 배정, 잡의 categorize 타임아웃, GPU 스위치. 운영·로컬 프로필의 서비스가 함께 읽는다.
 *
 * 어댑터 한쪽만 쓰는 값은 같은 prefix의 다른 클래스에 있다: Lambda 함수 이름은 [LambdaAiTaskProperties],
 * 로컬 대역 스크립트 자리는 [LocalProcessProperties]. 시간 상수는 전부 여기 있다 — Phase 4 실측 뒤 설정으로 조정한다.
 */
@ConfigurationProperties(prefix = "app.analysis")
data class AnalysisProperties(
    /** 임베더 EVENT 하나에 담는 사진 수. Lambda 15분 안에 넉넉히 끝나는 크기다. */
    val embedBatchSize: Int = 50,
    /** score Lambda 폴백 EVENT 하나에 담는 사진 수. GPU 워커가 못 낼 때만 쓰인다. */
    val scoreBatchSize: Int = 50,
    /** 동시에 떠 있는 임베더 배치 수 상한. 인프라의 embedder 예약 동시성이 이 값 이상이어야 한다. */
    val embedMaxInFlight: Int = 32,
    /** 배정한 뒤 이 시간이 지나도 벡터가 없으면 배정을 되돌려 다시 보낸다. 배치 하나의 실행 시간보다 길어야 한다. */
    val embedRedispatchAfter: Duration = Duration.ofMinutes(10),
    /** 사진 한 장을 이 횟수까지 배정한다. 넘으면 `photo_analysis.error`로 표시하고 기대 장수에서 뺀다. */
    val embedMaxAttempts: Int = 3,
    /** 이 시간 안에 발급된 PENDING 사진은 "아직 올라오는 중"으로 보고 점수 완료 판정을 미룬다. */
    val uploadQuietAfter: Duration = Duration.ofMinutes(2),
    /** categorize EVENT를 보낸 뒤 이 시간 안에 배정 행이 오지 않으면 다시 보낸다. 7천 장 categorize(약 70초)보다 넉넉하다. */
    val categorizeTimeout: Duration = Duration.ofMinutes(20),
    /** categorize를 이 횟수까지 부른다. 넘으면 잡을 FAILED로 닫는다. */
    val categorizeMaxAttempts: Int = 3,
    val gpu: Gpu = Gpu(),
) {

    /**
     * GPU score 워커. [enabled]면 스윕이 backlog를 보고 EC2 인스턴스(태그 `Name`=[tag])를 켜고, 워커의 유휴 30초 자기 정지가
     * 실패했을 때 [idleStopAfter] 무진행이면 끈다. 워커가 [fallbackAfter] 동안 뜨지 않거나 점수가 멈추면 score Lambda 폴백을
     * 갤러리당 [fallbackInterval]마다 보낸다. 꺼져 있으면 폴백만 돈다(score는 UPSERT라 겹쳐도 같은 값을 덮을 뿐이다).
     */
    data class Gpu(
        val enabled: Boolean = false,
        /** 워커 인스턴스를 찾는 EC2 `Name` 태그 값. */
        val tag: String = "wes-score-gpu",
        /** 켠 뒤 이 시간 안에는 끄지도, 폴백을 보내지도 않는다 — 부팅·모델 로드 시간이다. */
        val startGrace: Duration = Duration.ofMinutes(5),
        /** backlog가 0인데 워커가 켜져 있고 이 시간 동안 점수 진행이 없으면 끈다. 워커 자기 정지(30초)가 1차이고 이것은 안전망이다. */
        val idleStopAfter: Duration = Duration.ofMinutes(2),
        /** backlog가 있는데 워커가 이 시간 동안 뜨지 않거나, 켜져 있는데 이 시간 동안 점수가 늘지 않으면 Lambda 폴백. */
        val fallbackAfter: Duration = Duration.ofMinutes(10),
        /** 같은 갤러리에 폴백을 다시 보내는 최소 간격. */
        val fallbackInterval: Duration = Duration.ofMinutes(10),
    )
}
