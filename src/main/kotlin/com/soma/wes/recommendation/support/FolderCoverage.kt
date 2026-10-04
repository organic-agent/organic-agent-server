package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.FolderCoverageDto
import com.soma.wes.recommendation.dto.RecommendablePhotoDto
import java.time.LocalDateTime
import kotlin.math.ceil

/**
 * 폴더 하나의 추천 — 연사마다 한 장씩, 폴더 전체 장면을 고루.
 *
 *     조각 = 연사를 촬영 순서(takenAt)로 걸으며, 조각의 첫 컷과 거리가 [SAME_SHOT_DISTANCE]를 넘으면 새 조각
 *     후보 = 담은 사진이 든 조각을 통째로 뺀 나머지 조각마다 점수 최고 컷 (제외 사진은 대표가 될 수 없다)
 *     거르기 = 후보를 점수순으로 보며, 이미 남긴 후보와 거리가 [SAME_SHOT_DISTANCE] 미만이면 버린다
 *     n장 = 남은 후보가 n 이하면 전부, 넘으면 임베딩 평균 연결로 n묶음 → 묶음마다 점수 최고 1장
 *     순서 = 점수 내림차순, 동점은 photoId 오름차순
 *
 * 랜덤이 없다 — 같은 입력이면 같은 조각·같은 묶음·같은 순서다. 임베딩은 정규화돼 있다고 본다(내적 = 코사인).
 *
 * 근거(7,189장 갤러리 실측, 2026-10-04): categorize 는 8장 창 안에서 유사도 0.96 이상인 이웃을 사슬처럼 이어 연사를 만들어,
 * 포즈가 천천히 바뀌는 긴 세션이 한 연사가 된다(50장 넘는 연사가 사진 1/3). 큰 연사 안의 이웃 컷 거리는 중앙값 0.017로
 * 끊을 자리가 없고, 대신 첫 컷에서 멀어지는 정도가 연사마다 0.04~0.35로 다르다. 장수로 자르면 거의 같은 79장도 4조각이
 * 되므로 흘러간 만큼만 자른다. 거르기를 더하면 추천 안의 거의 같은 사진 쌍이 0이 됐다(장수로 자르기 27쌍).
 */
object FolderCoverage {

    /**
     * 같은 컷으로 보는 거리(1 − 코사인). categorize 의 연사 기준(`burst_threshold` = 유사도 0.96)과 같다 — 연사를 만든
     * 기준 그대로, 이웃끼리가 아니라 조각의 첫 컷과 비교해 사슬처럼 끝없이 이어지는 것을 끊는다.
     */
    const val SAME_SHOT_DISTANCE = 0.04

    /** 폴더 사진 수 대비 추천 장수. */
    const val CAP_RATIO = 0.1

    /** 촬영 순서 — 촬영 시각(없으면 뒤로), 같으면 photoId. `burstRank`는 대표 후보 순위라 순서로 쓰지 않는다. */
    private val SHOT_ORDER: Comparator<RecommendablePhotoDto> =
        compareBy<RecommendablePhotoDto, LocalDateTime?>(nullsLast()) { it.takenAt }.thenBy { it.photoId }

    /** 폴더 한 번의 추천 장수 — 폴더 사진의 10%, 최소 1장, 최대 그 갤러리에서 골라야 하는 장수. */
    fun cap(folderSize: Int, selectable: Int): Int =
        minOf(selectable, maxOf(1, ceil(folderSize * CAP_RATIO).toInt()))

    /**
     * [members]는 폴더의 분석된 사진 전부(담은 사진 포함)다 — 조각은 폴더 전체로 만들어야 같은 사진이 늘 같은 조각에 든다.
     * [excluded](거절·이상치·단체)는 조각에는 들지만 대표가 될 수 없다.
     */
    fun select(
        members: List<Int>,
        selected: Set<Int>,
        excluded: Set<Int>,
        rows: List<RecommendablePhotoDto>,
        score: DoubleArray,
        emb: Array<FloatArray>,
        n: Int,
    ): FolderCoverageDto {
        val better = compareByDescending<Int> { score[it] }.thenBy { rows[it].photoId }
        val pieces = pieces(members, rows, emb)
        val open = pieces.filter { piece -> piece.none { it in selected } }
        val reps = open.mapNotNull { piece -> piece.filter { it !in excluded && it !in selected }.minWithOrNull(better) }
            .sortedWith(better)
        val distinct = withoutNearDuplicates(reps, emb)
        val picks = when {
            distinct.size <= n -> distinct
            // 벡터가 빠진 후보가 있으면 거리를 믿을 수 없어 묶지 않고 점수순으로 자른다.
            distinct.any { emb[it].isEmpty() } -> distinct.take(n)
            else -> scenes(distinct, emb, n).map { scene -> scene.minWith(better) }
        }
        return FolderCoverageDto(
            picks = picks.sortedWith(better),
            pieces = pieces.size,
            skippedPieces = pieces.size - open.size,
            nearDuplicates = reps.size - distinct.size,
        )
    }

    /** 점수순 후보에서, 앞서 남긴 후보와 같은 컷으로 보이는 것을 버린다. 벡터가 없는 후보는 비교하지 않고 남긴다. */
    private fun withoutNearDuplicates(ranked: List<Int>, emb: Array<FloatArray>): List<Int> {
        val kept = mutableListOf<Int>()
        for (candidate in ranked) {
            val sameShot = emb[candidate].isNotEmpty() &&
                kept.any { emb[it].isNotEmpty() && 1 - dot(emb[it], emb[candidate]) < SAME_SHOT_DISTANCE }
            if (!sameShot) kept += candidate
        }
        return kept
    }

    /** 연사를 조각으로. 연사 번호가 없는 사진(-1)은 한 장이 한 조각이다 — 모르는 것끼리 한 연사로 뭉치지 않게. */
    fun pieces(members: List<Int>, rows: List<RecommendablePhotoDto>, emb: Array<FloatArray>): List<List<Int>> {
        val (unknown, known) = members.partition { rows[it].burstId < 0 }
        val bursts = known.groupBy { rows[it].burstId }.toSortedMap().values.map { burst ->
            burst.sortedWith(compareBy(SHOT_ORDER) { rows[it] })
        }
        return bursts.flatMap { split(it, emb) } + unknown.sortedBy { rows[it].photoId }.map { listOf(it) }
    }

    /**
     * 촬영 순서대로 걸으며 조각의 첫 컷과 거리가 [SAME_SHOT_DISTANCE]를 넘으면 새 조각을 연다. 같은 장면이면 수십 장도
     * 한 조각이고, 장면이 흘러간 만큼만 나뉜다. 벡터가 빠진 컷은 판단할 수 없어 지금 조각에 붙인다.
     */
    private fun split(ordered: List<Int>, emb: Array<FloatArray>): List<List<Int>> {
        val pieces = mutableListOf(mutableListOf(ordered.first()))
        for (shot in ordered.drop(1)) {
            val anchor = pieces.last().first()
            val moved = emb[anchor].isNotEmpty() && emb[shot].isNotEmpty() &&
                1 - dot(emb[anchor], emb[shot]) > SAME_SHOT_DISTANCE
            if (moved) pieces += mutableListOf(shot) else pieces.last() += shot
        }
        return pieces
    }

    /**
     * 후보를 임베딩 평균 연결 계층 클러스터로 [n]묶음. 가장 가까운 두 묶음부터 합치고(동점은 앞 번호),
     * 합친 묶음과의 거리는 크기 가중 평균이다(Lance-Williams).
     */
    private fun scenes(reps: List<Int>, emb: Array<FloatArray>, n: Int): List<List<Int>> {
        val m = reps.size
        val distance = Array(m) { a -> DoubleArray(m) { b -> 1 - dot(emb[reps[a]], emb[reps[b]]) } }
        val groups = Array(m) { mutableListOf(reps[it]) }
        val alive = BooleanArray(m) { true }
        repeat(m - n) {
            var bestA = -1
            var bestB = -1
            var bestDistance = Double.POSITIVE_INFINITY
            for (a in 0 until m) {
                if (!alive[a]) continue
                for (b in a + 1 until m) {
                    if (alive[b] && distance[a][b] < bestDistance) {
                        bestDistance = distance[a][b]
                        bestA = a
                        bestB = b
                    }
                }
            }
            val sizeA = groups[bestA].size.toDouble()
            val sizeB = groups[bestB].size.toDouble()
            for (k in 0 until m) {
                if (!alive[k] || k == bestA || k == bestB) continue
                val merged = (sizeA * distance[bestA][k] + sizeB * distance[bestB][k]) / (sizeA + sizeB)
                distance[bestA][k] = merged
                distance[k][bestA] = merged
            }
            groups[bestA] += groups[bestB]
            alive[bestB] = false
        }
        return (0 until m).filter { alive[it] }.map { groups[it] }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
