package com.soma.wes.cluster.repository

import com.soma.wes.cluster.dto.SimilarPairDto
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * 임계값 이상으로 닮은 사진 쌍을 찾는다.
 */
@Repository
class PhotoSimilarityRepository(
    private val jdbcClient: JdbcClient,
) {

    /**
     * 갤러리 안의 모든 쌍을 정확히 비교한다(N²).
     *
     * 근사 최근접(HNSW 인덱스)으로 바꾸면 훨씬 빠르지만, 누락된 간선 하나가 곧 잘못 쪼개진
     * 묶음이 된다. 클러스터는 간선의 연결 요소라서 정확도 손실이 결과에 그대로 드러나므로,
     * 갤러리 규모가 커지기 전까지는 정확도를 택한다.
     *
     * 임계값은 시간 조건부 이중 구조다. 우연히 닮은 다른 장면이 촬영 시각까지 가까울 확률은
     * 낮으므로, 촬영 시각이 [windowSeconds] 안인 쌍은 [lenientDistance]까지 허용해 같은 장면의
     * 다른 프레이밍(과분할)을 묶고, 창 밖이거나 촬영 시각이 없는 쌍은 [strictDistance]로
     * 체이닝 오병합을 계속 막는다.
     *
     * `b.id > a.id`로 한쪽 방향만 본다. 쌍을 두 번 세도 union 결과는 같지만 간선 수가 두 배가 된다.
     *
     * 간선에는 거리와 시간 관계(창 안 / 창 밖 / 측정 불가)가 함께 실린다 — 창 밖이 실측된
     * 간선에만 상호 kNN 필터를 거는 후처리([com.soma.wes.cluster.support.MutualKnnEdgeFilter])가
     * 이 둘을 쓴다.
     *
     * 네이티브 SQL은 `Photo`의 `@SQLRestriction`을 타지 않으므로 휴지통 사진(`deleted_at`)을
     * 여기서 직접 걸러야 한다. 빼먹으면 지운 사진이 묶음의 대표로 되살아난다.
     *
     * 벡터는 `photos`가 아니라 `photo_analysis`에 있다(모델 파생값이라 정체성과 생명주기가
     * 다르다). 사진마다 분석 행을 조인하고, 벡터가 없는 행은 조인 조건에서 떨어진다.
     *
     * @param strictDistance 모든 쌍에 적용하는 코사인 '거리' 상한. `<=>`가 돌려주는 것이
     *   유사도가 아니라 거리라 유사도 0.9는 거리 0.1이다.
     * @param lenientDistance 촬영 시각이 창 안인 쌍에만 허용하는 더 큰 거리 상한.
     *   [strictDistance]보다 작으면 조건이 strict에 흡수되어 아무 쌍도 더해지지 않는다.
     * @param windowSeconds 두 사진의 촬영 시각 차이가 이 값 이하면 같은 시간대로 본다.
     *   기본값 0이면 시간 게이트 없이 strict 임계값 하나로 동작한다.
     */
    fun findSimilarPairs(
        galleryId: Long,
        strictDistance: Double,
        lenientDistance: Double = strictDistance,
        windowSeconds: Long = 0,
    ): List<SimilarPairDto> =
        jdbcClient.sql(
            """
            SELECT a.id AS left_id,
                   b.id AS right_id,
                   (ea.embedding <=> eb.embedding) AS distance,
                   CASE
                       WHEN a.taken_at IS NULL OR b.taken_at IS NULL THEN 'UNKNOWN'
                       WHEN abs(extract(epoch FROM (a.taken_at - b.taken_at))) <= :windowSeconds
                           THEN 'WITHIN_WINDOW'
                       ELSE 'OUT_OF_WINDOW'
                   END AS time_relation
            FROM photos a
                     JOIN photo_analysis ea
                          ON ea.photo_id = a.id
                              AND ea.embedding IS NOT NULL
                     JOIN photos b
                          ON b.gallery_id = a.gallery_id
                              AND b.id > a.id
                              AND b.deleted_at IS NULL
                     JOIN photo_analysis eb
                          ON eb.photo_id = b.id
                              AND eb.embedding IS NOT NULL
            WHERE a.gallery_id = :galleryId
              AND a.deleted_at IS NULL
              AND ((ea.embedding <=> eb.embedding) <= :strictDistance
                  OR (a.taken_at IS NOT NULL AND b.taken_at IS NOT NULL
                      AND abs(extract(epoch FROM (a.taken_at - b.taken_at))) <= :windowSeconds
                      AND (ea.embedding <=> eb.embedding) <= :lenientDistance))
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("strictDistance", strictDistance)
            .param("lenientDistance", lenientDistance)
            .param("windowSeconds", windowSeconds)
            .query { rs, _ ->
                SimilarPairDto(
                    leftId = rs.getLong("left_id"),
                    rightId = rs.getLong("right_id"),
                    distance = rs.getDouble("distance"),
                    timeRelation = SimilarPairDto.TimeRelation.valueOf(rs.getString("time_relation")),
                )
            }
            .list()
}
