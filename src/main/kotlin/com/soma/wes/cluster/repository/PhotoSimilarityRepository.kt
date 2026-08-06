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
     * `b.id > a.id`로 한쪽 방향만 본다. 쌍을 두 번 세도 union 결과는 같지만 간선 수가 두 배가 된다.
     *
     * @param maxDistance 코사인 '거리' 상한. `<=>`가 돌려주는 것이 유사도가 아니라 거리라
     *   유사도 0.9는 거리 0.1이다.
     */
    fun findSimilarPairs(galleryId: Long, maxDistance: Double): List<Pair<Long, Long>> =
        jdbcClient.sql(
            """
            SELECT a.id AS left_id, b.id AS right_id
            FROM photos a
                     JOIN photos b
                          ON b.gallery_id = a.gallery_id
                              AND b.id > a.id
                              AND b.embedding IS NOT NULL
            WHERE a.gallery_id = :galleryId
              AND a.embedding IS NOT NULL
              AND (a.embedding <=> b.embedding) <= :maxDistance
            """.trimIndent(),
        )
            .param("galleryId", galleryId)
            .param("maxDistance", maxDistance)
            .query { rs, _ -> rs.getLong("left_id") to rs.getLong("right_id") }
            .list()
}
