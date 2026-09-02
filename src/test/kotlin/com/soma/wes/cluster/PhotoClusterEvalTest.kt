package com.soma.wes.cluster

import com.soma.wes.cluster.config.ClusterProperties
import com.soma.wes.cluster.dto.SimilarPairDto
import com.soma.wes.cluster.repository.PhotoSimilarityRepository
import com.soma.wes.cluster.support.MutualKnnEdgeFilter
import com.soma.wes.cluster.support.UnionFind
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoMetadata
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.repository.WorkspaceRepository
import com.soma.wes.support.TestSequence
import com.soma.wes.support.TestcontainersConfiguration
import java.time.LocalDateTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.databind.ObjectMapper

/**
 * 클러스터링 품질 평가 하네스 — 사람이 라벨링한 정답 묶음 대비 pairwise precision/recall/F1.
 *
 * 평가셋(`src/test/resources/eval/gallery-*.json`)은 실제 갤러리에서 임베딩·촬영 시각·정답
 * 라벨만 export한 것이다. 만드는 절차와 라벨링 기준은 그 디렉토리의 README 참조.
 * fixture가 하나도 없으면 스킵된다 — 빈 저장소를 체크아웃한 CI가 깨지지 않기 위해서다.
 *
 * 운영 경로([PhotoSimilarityRepository]의 pgvector 쌍 질의 + [UnionFind] 연결 요소)를
 * 그대로 태운다. 거리 계산이나 묶음 규칙을 흉내 내면 이후 쿼리 개선(시간 게이트 등)이
 * 평가에서 빠진다.
 *
 * **품질 수치를 단언하지 않는다** ([PhotoClusterScaleTest]와 같은 이유 — 지표는 판단의
 * 근거이지 회귀 조건이 아니다). 측정값은 로그로 남기고, 기준선은
 * `docs/notes/clustering-eval-baseline.md`에 기록한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Tag("eval")
class PhotoClusterEvalTest @Autowired constructor(
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val photoSimilarityRepository: PhotoSimilarityRepository,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val objectMapper: ObjectMapper,
    private val clusterProperties: ClusterProperties,
    private val workspaceRepository: WorkspaceRepository,
) {

    @Test
    fun `평가셋 갤러리마다 임계값을 스윕하며 pairwise 지표를 잰다`() {
        val fixtures = loadFixtures()
        assumeTrue(fixtures.isNotEmpty()) {
            "평가셋 fixture가 없다 — src/test/resources/eval/README.md 절차로 생성한다."
        }

        fixtures.forEach { fixture -> evaluate(fixture) }
    }

    private fun evaluate(fixture: EvalGallery) {
        val galleryId = createGallery()
        val labelsByPhotoId = insertPhotos(galleryId, fixture)
        val photoIds = labelsByPhotoId.keys.sorted()
        val truePairs = truePairs(photoIds, labelsByPhotoId)

        val groupCount = labelsByPhotoId.values.filterNotNull().distinct().size
        println(
            "[cluster-eval] 갤러리 ${fixture.galleryId}: 사진 ${photoIds.size}장, " +
                "정답 그룹 ${groupCount}개, 정답 쌍 ${truePairs.size}개",
        )

        val results = THRESHOLDS.map { threshold ->
            // 유사도 0.9 = 거리 0.1.
            val edges = photoSimilarityRepository.findSimilarPairs(galleryId, 1.0 - threshold)
            val predictedPairs = connectedPairs(photoIds, edges)
            threshold to score(predictedPairs, truePairs)
        }

        results.forEach { (threshold, metrics) ->
            println("[cluster-eval]   threshold=%.2f  %s".format(threshold, metrics.report()))
        }
        val best = results.maxBy { (_, metrics) -> metrics.f1 }
        println("[cluster-eval]   best: threshold=%.2f F1=%.3f".format(best.first, best.second.f1))

        // 품질은 단언하지 않지만 방향은 지킨다 — 임계값을 올렸는데 예측 쌍이 늘었다면
        // 거리 부호가 뒤집혔거나 지표 계산이 잘못된 것이다.
        val predictedCounts = results.map { (_, metrics) -> metrics.predictedCount }
        assertThat(predictedCounts)
            .describedAs("임계값이 오르면 예측 쌍은 줄거나 같아야 한다")
            .isEqualTo(predictedCounts.sortedDescending())

        evaluateLevels(galleryId, photoIds, truePairs)
    }

    /**
     * 운영 레벨 번들 5개를 설정 그대로 측정한다 — 이 표가 배포되는 프리셋의 품질이다.
     * 시간 창 밖 상호 kNN 필터([MutualKnnEdgeFilter])까지 운영 경로 그대로 태운다.
     * 번들 재선정이 필요하면(임베딩 교체 등) 이 메서드를 임시로 그리드 스윕으로 바꿔 돌린다
     * (`docs/notes/clustering-eval-phase2.md`의 절차 참조).
     */
    private fun evaluateLevels(
        galleryId: Long,
        photoIds: List<Long>,
        truePairs: Set<Pair<Long, Long>>,
    ) {
        val results = clusterProperties.levels.toSortedMap().map { (level, bundle) ->
            val edges = photoSimilarityRepository.findSimilarPairs(
                galleryId = galleryId,
                strictDistance = 1.0 - bundle.strictThreshold,
                lenientDistance = 1.0 - bundle.lenientThreshold,
                windowSeconds = bundle.windowSeconds,
            )
            val filtered = MutualKnnEdgeFilter.filter(edges = edges, k = bundle.knnK)
            level to score(connectedPairs(photoIds, filtered), truePairs)
        }

        results.forEach { (level, metrics) ->
            println("[cluster-eval]   level=$level  ${metrics.report()}")
        }

        // 레벨 번들은 중첩(strict·lenient 상승, window 하강)이어야 한다 — 그래야 "레벨을
        // 올리면 반드시 더 잘게"가 성립한다. kNN 필터는 이웃 순위가 레벨마다 달라 중첩을
        // 엄밀히 보장하지 못하므로, 설정이든 필터든 단조성을 깨면 여기서 걸린다.
        val predictedCounts = results.map { (_, metrics) -> metrics.predictedCount }
        assertThat(predictedCounts)
            .describedAs("레벨이 오르면 예측 쌍은 줄거나 같아야 한다")
            .isEqualTo(predictedCounts.sortedDescending())
    }

    private fun loadFixtures(): List<EvalGallery> =
        PathMatchingResourcePatternResolver()
            .getResources("classpath:eval/gallery-*.json")
            .map { resource -> objectMapper.readValue(resource.inputStream, EvalGallery::class.java) }
            .sortedBy { it.galleryId }

    private fun insertPhotos(galleryId: Long, fixture: EvalGallery): Map<Long, String?> {
        val photos = fixture.photos.mapIndexed { index, photo ->
            Photo(
                galleryId = galleryId,
                storageKey = "eval/$galleryId/${TestSequence.next()}.jpg",
                originalFileName = photo.fileName,
                contentType = "image/jpeg",
                displayOrder = index,
            ).also {
                it.markEmbedded()
                photo.takenAt?.let { takenAt -> it.applyMetadata(PhotoMetadata(takenAt = takenAt)) }
            }
        }
        photoRepository.saveAll(photos)
        photoRepository.flush()
        photoAnalysisRepository.saveAll(
            photos.zip(fixture.photos) { photo, source ->
                PhotoAnalysis.embeddedBy(
                    photoId = photo.requiredId,
                    vector = source.embedding,
                    model = "facebook/dinov3-vitb16-pretrain-lvd1689m",
                )
            },
        )
        photoAnalysisRepository.flush()

        return photos.withIndex().associate { (index, photo) ->
            photo.requiredId to fixture.photos[index].groupLabel
        }
    }

    /** 같은 라벨을 단 사진들의 모든 쌍. 라벨 없는 사진(싱글턴)은 어떤 쌍에도 들어가지 않는다. */
    private fun truePairs(photoIds: List<Long>, labelsByPhotoId: Map<Long, String?>): Set<Pair<Long, Long>> =
        photoIds.groupBy { labelsByPhotoId[it] }
            .filterKeys { it != null }
            .values
            .flatMapTo(mutableSetOf()) { group -> allPairs(group) }

    /** 간선의 연결 요소를 구해, 같은 묶음에 든 사진들의 모든 쌍으로 펼친다. */
    private fun connectedPairs(photoIds: List<Long>, edges: List<SimilarPairDto>): Set<Pair<Long, Long>> {
        val indexById = photoIds.withIndex().associate { (index, id) -> id to index }
        val unionFind = UnionFind(photoIds.size)
        edges.forEach { edge ->
            unionFind.union(indexById.getValue(edge.leftId), indexById.getValue(edge.rightId))
        }

        return photoIds.groupBy { unionFind.find(indexById.getValue(it)) }
            .values
            .flatMapTo(mutableSetOf()) { component -> allPairs(component) }
    }

    private fun allPairs(ids: List<Long>): List<Pair<Long, Long>> {
        val sorted = ids.sorted()
        return sorted.flatMapIndexed { index, left ->
            sorted.drop(index + 1).map { right -> left to right }
        }
    }

    private fun score(predicted: Set<Pair<Long, Long>>, truth: Set<Pair<Long, Long>>): PairwiseMetrics {
        val hit = predicted.count { it in truth }
        return PairwiseMetrics(
            predictedCount = predicted.size,
            trueCount = truth.size,
            hitCount = hit,
        )
    }

    private fun createGallery(): Long {
        val suffix = TestSequence.next()
        val workspace = workspaceRepository.save(Workspace.studio("클러스터 평가"))
        val studio = studioRepository.save(
            Studio(
                userId = workspace.requiredId,
                name = "클러스터 평가",
                galleryUrl = "cluster-eval-$suffix",
            ),
        )
        val gallery = galleryRepository.save(
            Gallery(studioId = studio.id, title = "평가 갤러리"),
        )
        return checkNotNull(gallery.id)
    }

    companion object {

        /** 단일 임계값(시간 게이트 없음) 스윕 범위 — 임베딩 자체의 분리력을 보는 기준선이다. */
        private val THRESHOLDS = (80..98 step 2).map { it / 100.0 }
    }

    private data class EvalGallery(
        val galleryId: Long,
        val embeddingDimension: Int,
        val photos: List<EvalPhoto>,
    ) {
        data class EvalPhoto(
            val photoId: Long,
            val fileName: String,
            val takenAt: LocalDateTime?,
            val groupLabel: String?,
            val embedding: FloatArray,
        )
    }

    private data class PairwiseMetrics(
        val predictedCount: Int,
        val trueCount: Int,
        val hitCount: Int,
    ) {
        /** 예측 쌍이 없으면 틀린 쌍도 없으므로 1로 둔다 (정답 쌍이 없는 recall도 같다). */
        val precision: Double = if (predictedCount == 0) 1.0 else hitCount.toDouble() / predictedCount
        val recall: Double = if (trueCount == 0) 1.0 else hitCount.toDouble() / trueCount
        val f1: Double = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

        fun report(): String =
            "P=%.3f  R=%.3f  F1=%.3f  (예측 쌍 %d)".format(precision, recall, f1, predictedCount)
    }
}
