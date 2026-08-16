package com.soma.wes.cluster.support

import com.soma.wes.cluster.dto.SimilarPairDto

/**
 * 시간 창 밖이 실측된 간선에만 상호 kNN 조건을 요구하는 필터 — 체이닝의 비대칭 제어.
 *
 * single-linkage 체이닝은 방향에 따라 약이자 독이다. 시간 창 **안**에서는 풀샷↔클로즈업을
 * 중간 프레이밍이 이어주는 recall의 동력이라 그대로 두고, 창 **밖**에서는 우연히 닮은 다리
 * 사진 한 장이 다른 두 장면을 합치는 오병합의 주범이라 상호성(서로가 서로의 k-이웃)을
 * 요구해 자른다. 다리 사진의 상대는 대개 자기 장면의 연사가 더 가까워 다리를 k-이웃으로
 * 되받지 않으므로, 상호성 요구만으로 다리 간선이 떨어져 나간다.
 *
 * 촬영 시각을 잴 수 없는 간선([SimilarPairDto.TimeRelation.UNKNOWN])은 자르지 않는다.
 * 필터의 전제는 "시간이 멀면 우연"인데, EXIF가 없으면 멀다고 단정할 근거가 없다. 여기서
 * 자르면 EXIF 전무 갤러리의 연사 묶음(모든 쌍이 임계값을 넘는 클리크)이 상호 최근접
 * 매칭으로 조각나 두 장씩 흩어진다.
 *
 * 이웃 순위는 시간 관계를 가리지 않고 임계값을 통과한 모든 간선으로 매긴다 — 연사가 많은
 * 사진일수록 이웃 자리가 연사로 차서 창 밖 간선이 밀려나는데, 그 사진에게 창 밖 간선은
 * 실제로 더 의심스러우므로 이것이 원하는 동작이다.
 */
object MutualKnnEdgeFilter {

    /** @param k 0 이하면 필터를 끄고 간선을 그대로 돌려준다. */
    fun filter(edges: List<SimilarPairDto>, k: Int): List<SimilarPairDto> {
        if (k <= 0) {
            return edges
        }

        val nearestIds = nearestNeighborIds(edges, k)
        return edges.filter { edge ->
            edge.timeRelation != SimilarPairDto.TimeRelation.OUT_OF_WINDOW ||
                (nearestIds.getValue(edge.leftId).contains(edge.rightId) &&
                    nearestIds.getValue(edge.rightId).contains(edge.leftId))
        }
    }

    private fun nearestNeighborIds(edges: List<SimilarPairDto>, k: Int): Map<Long, Set<Long>> {
        val neighborsById = mutableMapOf<Long, MutableList<Pair<Long, Double>>>()
        edges.forEach { edge ->
            neighborsById.getOrPut(edge.leftId) { mutableListOf() }.add(edge.rightId to edge.distance)
            neighborsById.getOrPut(edge.rightId) { mutableListOf() }.add(edge.leftId to edge.distance)
        }

        return neighborsById.mapValues { (_, neighbors) ->
            // 거리가 같으면 id가 작은 쪽 — 같은 요청을 두 번 보내도 같은 간선이 살아남아야 한다.
            neighbors.sortedWith(compareBy({ it.second }, { it.first }))
                .take(k)
                .mapTo(mutableSetOf()) { it.first }
        }
    }
}
