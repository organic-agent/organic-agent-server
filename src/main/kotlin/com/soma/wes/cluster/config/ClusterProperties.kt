package com.soma.wes.cluster.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 클러스터링 레벨 프리셋.
 *
 * 사용자 손잡이는 연속 임계값이 아니라 레벨(1 = 크게 묶기 … 5 = 잘게 묶기)이고, 각 레벨은
 * 서버가 소유한 파라미터 번들이다. 수치를 API에 노출하지 않으므로 알고리즘·임베딩이 바뀌어도
 * 레벨의 의미는 유지되고, 번들 값만 재선정하면 된다. 값은 감이 아니라 평가 하네스
 * (PhotoClusterEvalTest)의 그리드 스윕에서 고른 운영점이다 — 근거는
 * `docs/notes/clustering-eval-phase2.md`.
 *
 * 레벨이 오를수록 strict·lenient는 오르고 window는 줄어야 한다. 그래야 상위 레벨의 간선이
 * 하위 레벨의 부분집합이 되어 "레벨을 올리면 반드시 더 잘게"가 성립한다. 상호 kNN 필터
 * ([knnK][ClusterLevel.knnK])가 끼면 이웃 순위가 레벨마다 달라져 부분집합이 엄밀히 보장되지는
 * 않으므로, 단조성은 평가 하네스가 실측으로 검증한다.
 */
@ConfigurationProperties(prefix = "app.cluster")
data class ClusterProperties(
    val defaultLevel: Int,
    val levels: Map<Int, ClusterLevel>,
) {

    data class ClusterLevel(
        /** 시간 무관하게 모든 쌍에 적용하는 코사인 유사도 하한. 묶음 입도의 주 손잡이다. */
        val strictThreshold: Double,
        /** 촬영 시각이 [windowSeconds] 안인 쌍에만 허용하는 느슨한 유사도 하한. */
        val lenientThreshold: Double,
        /** 두 사진을 같은 시간대로 보는 촬영 시각 차이 상한. */
        val windowSeconds: Long,
        /**
         * 촬영 시각이 실측으로 창 밖인 간선에 요구하는 상호 kNN의 k
         * ([com.soma.wes.cluster.support.MutualKnnEdgeFilter]). 0이면 필터를 끈다.
         */
        val knnK: Int,
    )
}
