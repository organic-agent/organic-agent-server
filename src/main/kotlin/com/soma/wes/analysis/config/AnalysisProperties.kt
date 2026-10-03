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
    /**
     * ANALYZING 잡의 진행(대상·임베딩·점수 장수)이 이 시간 동안 바뀌지 않으면 멈춘 것으로 본다. 임베더가 한 사진을 포기하기까지의
     * 시간([embedRedispatchAfter] × [embedMaxAttempts])보다 길어야 한다 — 짧으면 다시 배정돼 돌고 있는 배치를 멈춤으로 읽는다.
     */
    val analyzingStallAfter: Duration = Duration.ofMinutes(35),
    /**
     * 멈췄을 때 뒤처진 사진(점수 없는 대상)이 대상의 이 비율 이하면 그 사진만 떼어 내고 잡은 계속 간다. 넘으면 사진이 아니라
     * 실행기의 문제로 보고 잡을 닫는다. 대상이 적은 갤러리에서도 한 장은 떼어 낼 수 있다.
     */
    val stallDetachRatio: Double = 0.05,
    /** CATEGORIZING 에 들어간 뒤 이 시간이 지나면 잡을 닫는다. [categorizeTimeout] × [categorizeMaxAttempts]에 여유를 더한 값이다. */
    val categorizingDeadline: Duration = Duration.ofMinutes(70),
    /** 폴더 만들기가 예상 밖 예외로 이 횟수만큼 실패하면 잡을 닫는다. */
    val materializeMaxAttempts: Int = 3,
    /** 한 갤러리에 score Lambda 폴백을 이 횟수까지 보낸다. 그 뒤에도 점수가 없으면 진행 감시가 처리한다. */
    val scoreFallbackMax: Int = 3,
    /** 관리자 백오피스 주소. 실패 사진 알림이 그 갤러리를 여는 링크(`/resources?type=GALLERY&id=`)를 이 위에 만든다. */
    val backofficeBaseUrl: String = "https://admin.easyselect.kr",
    /**
     * 마지막 사진이 올라온 뒤 이 시간이 지나야 서버가 분석 잡을 만든다. 나눠 올리는 중간에 잡이 만들어지지 않게 하는 숨 고르기다.
     * web 이 업로드 직후 직접 요청하면 이 시간을 기다리지 않는다.
     */
    val autoAnalysisQuietAfter: Duration = Duration.ofMinutes(1),
    /**
     * 서버가 스스로 잡을 만드는 것은 이 시간 안에 사진이 올라온 갤러리뿐이다. 배포하는 순간 분석한 적 없는 옛 갤러리 전부에
     * 잡이 만들어지는 것을 막고, 5초마다 도는 조회가 최근 사진만 읽게 한다.
     */
    val autoAnalysisWindow: Duration = Duration.ofHours(6),
    /**
     * 일시적 실패([com.soma.wes.analysis.domain.AnalysisFailureCode.transient])로 닫힌 잡을 다시 돌리기까지의 대기.
     * 길이가 곧 자동 재시도 횟수다 — 첫 실패 뒤 첫 값, 그 재시도도 실패하면 둘째 값. 다 쓰면 사용자의 요청을 기다린다.
     */
    val autoRetryDelays: List<Duration> = listOf(Duration.ofMinutes(2), Duration.ofMinutes(10)),
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
