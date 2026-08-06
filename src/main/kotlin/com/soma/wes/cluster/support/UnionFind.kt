package com.soma.wes.cluster.support

/**
 * 간선으로 이어진 것들을 한 덩어리로 모으는 자료구조.
 *
 * 클러스터링이 "임계값을 넘는 쌍을 간선으로 보고 연결 요소를 찾는" 문제라서 쓴다. 덕분에
 * A-B가 닮고 B-C가 닮으면 A-C가 임계값에 못 미쳐도 셋이 한 묶음이 된다.
 */
class UnionFind(size: Int) {

    private val parent = IntArray(size) { it }

    /** 트리가 한쪽으로 길어지지 않도록 작은 쪽을 큰 쪽에 붙인다. */
    private val rank = IntArray(size)

    fun find(node: Int): Int {
        var root = node
        while (parent[root] != root) {
            root = parent[root]
        }

        // 경로 압축. 수천 장 × 수만 간선에서 find가 반복 호출되므로, 한 번 올라간 길은
        // 곧바로 루트에 직결시켜 다음 호출이 같은 길을 다시 걷지 않게 한다.
        var current = node
        while (parent[current] != root) {
            val next = parent[current]
            parent[current] = root
            current = next
        }
        return root
    }

    fun union(left: Int, right: Int) {
        val leftRoot = find(left)
        val rightRoot = find(right)
        if (leftRoot == rightRoot) {
            return
        }

        when {
            rank[leftRoot] < rank[rightRoot] -> parent[leftRoot] = rightRoot
            rank[leftRoot] > rank[rightRoot] -> parent[rightRoot] = leftRoot
            else -> {
                parent[rightRoot] = leftRoot
                rank[leftRoot]++
            }
        }
    }
}
