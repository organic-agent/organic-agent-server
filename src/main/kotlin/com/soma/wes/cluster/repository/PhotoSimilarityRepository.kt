package com.soma.wes.cluster.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * 임계값 이상으로 닮은 사진 쌍을 찾는다.
 *
 * JPA가 아니라 [JdbcClient]를 쓰는 이유는 pgvector의 `<=>` 연산자 때문이다. JPQL에는 이 연산자가
 * 없고, 있다 해도 결과가 엔티티가 아니라 id 쌍이라 영속성 컨텍스트에 올릴 것이 없다.
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
     * 네이티브 SQL은 `Photo`의 `@SQLRestriction`을 타지 않으므로 휴지통 사진(`deleted_at`)을
     * 여기서 직접 걸러야 한다. 빼먹으면 지운 사진이 묶음의 대표로 되살아난다.
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
    ): List<Pair<Long, Long>> =
        jdbcClient.sql(
            """
            SELECT a.id AS left_id, b.id AS right_id
            FROM photos a
                     JOIN photos b
                          ON b.gallery_id = a.gallery_id
                              AND b.id > a.id
                              AND b.embedding IS NOT NULL
                              AND b.deleted_at IS NULL
            WHERE a.gallery_id = :galleryId
              AND a.embedding IS NOT NULL
              AND a.deleted_at IS NULL
              AND ((a.embedding <=> b.embedding) <= :strictDistance
                  OR (a.taken_at IS NOT NULL AND b.taken_at IS NOT NULL
                      AND abs(extract(epoch FROM (a.taken_at - b.taken_at))) <= :windowSeconds
                      AND (a.embedding <=> b.embedding) <= :lenientDistance))
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("strictDistance", strictDistance)
            .param("lenientDistance", lenientDistance)
            .param("windowSeconds", windowSeconds)
            .query { rs, _ -> rs.getLong("left_id") to rs.getLong("right_id") }
            .list()
}
