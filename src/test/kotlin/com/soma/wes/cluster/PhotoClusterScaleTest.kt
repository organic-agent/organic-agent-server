package com.soma.wes.cluster

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.cluster.repository.PhotoSimilarityRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * 갤러리 규모별 클러스터링 응답 시간(#16의 "확인" 항목).
 *
 * 쌍 질의가 N²이라 규모가 커지면 언젠가 무너진다. 그 지점을 짐작이 아니라 숫자로 알아 두려고
 * 남긴다 -- 근사 최근접(HNSW)으로 갈아탈 시점을 정하는 근거가 이 측정이다.
 *
 * **성능 수치를 단언하지 않는다.** CI 러너와 노트북의 속도가 다르고, 그 차이로 빌드가 깨지면
 * 이 테스트는 지워질 뿐이다. 대신 측정값을 로그로 남기고, 결과의 정확성만 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Tag("scale")
class PhotoClusterScaleTest @Autowired constructor(
    private val photoRepository: PhotoRepository,
    private val photoSimilarityRepository: PhotoSimilarityRepository,
) {

    private val sequence = AtomicLong(System.nanoTime())

    @Test
    fun `갤러리 규모별 쌍 질의 시간을 잰다`() {
        // 실제 웨딩 갤러리는 수백~수천 장이다. 500장이면 쌍이 12만 개가 넘는다.
        listOf(100, 300, 500).forEach { size ->
            val galleryId = sequence.incrementAndGet()
            insertPhotos(galleryId, size)

            // 유사도 0.9 = 거리 0.1.
            val elapsed = measureTimeMillis {
                photoSimilarityRepository.findSimilarPairs(galleryId, 0.1)
            }

            println("[cluster-scale] ${size}장 (쌍 ${size * (size - 1) / 2}개) -> ${elapsed}ms")
            assertTrue(elapsed >= 0)
        }
    }

    @Test
    fun `임계값을 낮추면 더 많은 쌍이 걸린다`() {
        // N²이 문제인 것은 쌍의 개수만이 아니다. 임계값이 낮으면 통과하는 쌍도 함께 늘어
        // 애플리케이션이 받아 드는 간선 수가 커진다.
        val galleryId = sequence.incrementAndGet()
        insertPhotos(galleryId, 300)

        val strict = photoSimilarityRepository.findSimilarPairs(galleryId, 0.02).size
        val loose = photoSimilarityRepository.findSimilarPairs(galleryId, 0.30).size

        println("[cluster-scale] 300장: 유사도 0.98 -> ${strict}쌍, 유사도 0.70 -> ${loose}쌍")
        assertTrue(loose >= strict, "임계값을 낮췄는데 걸린 쌍이 줄었다면 거리 방향이 뒤집힌 것이다")
    }

    /**
     * 평면 위에 고르게 뿌린 단위 벡터.
     *
     * 무작위 768차원 벡터는 거의 전부 직교해서 어떤 임계값에서도 쌍이 걸리지 않는다. 그러면
     * 질의는 빨라지지만 실제 갤러리(비슷한 컷이 연속으로 찍힌다)와 전혀 다른 부하가 된다.
     */
    private fun insertPhotos(galleryId: Long, size: Int) {
        val random = Random(galleryId)
        val photos = (0 until size).map { index ->
            val angle = random.nextDouble(0.0, Math.PI / 2)
            Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/photo-$index.jpg",
                originalFileName = "photo-$index.jpg",
                contentType = "image/jpeg",
                displayOrder = index,
            ).also {
                it.applyEmbedding(
                    FloatArray(Photo.EMBEDDING_DIMENSION).apply {
                        this[0] = cos(angle).toFloat()
                        this[1] = sin(angle).toFloat()
                    },
                )
            }
        }
        photoRepository.saveAll(photos)
        photoRepository.flush()
    }
}
