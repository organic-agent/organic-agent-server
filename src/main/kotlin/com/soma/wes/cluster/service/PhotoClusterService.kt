package com.soma.wes.cluster.service

import com.soma.wes.cluster.config.ClusterProperties
import com.soma.wes.cluster.dto.response.PhotoClusterResponse
import com.soma.wes.cluster.dto.response.PhotoClustersResponse
import com.soma.wes.cluster.exception.ClusterErrorCode
import com.soma.wes.cluster.exception.ClusterException
import com.soma.wes.cluster.repository.PhotoSimilarityRepository
import com.soma.wes.cluster.support.UnionFind
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 레벨 프리셋이 정하는 기준 이상으로 닮은 사진들을 한 덩어리로 묶어 돌려준다.
 *
 * 기준을 넘는 쌍을 간선으로 보고 연결 요소를 찾는 방식이라, A-B가 닮고 B-C가 닮으면 A-C가
 * 기준에 못 미쳐도 셋이 한 묶음이 된다. 같은 인물·장면을 모으는 데는 이 동작이 맞다 — 원본
 * 수천 장을 한 장씩 넘겨보지 않고 묶음의 대표만 훑게 하는 것이 목적이기 때문이다.
 *
 * **저장하지 않는다.** 레벨이 손잡이라서 사용자가 값을 바꿔가며 여러 번 부르고, 그때마다
 * 결과가 통째로 달라진다. 확정된 묶음은 [com.soma.wes.folder.service.PhotoFolderService]가
 * 폴더로 고정한다.
 */
@Service
class PhotoClusterService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoRepository: PhotoRepository,
    private val photoSimilarityRepository: PhotoSimilarityRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: ClusterProperties,
) {

    /**
     * @param level 1(크게 묶기)~5(잘게 묶기). 생략하면 서버 기본 레벨을 쓴다.
     *   각 레벨의 실제 파라미터(유사도 임계값·촬영 시각 창)는 [ClusterProperties]가 소유한다.
     */
    // 한 응답을 만드는 데 질의가 셋(미임베딩 수 · 사진 목록 · 유사 쌍)이다. 기본
    // READ COMMITTED에서는 질의마다 스냅샷이 새로 잡혀서, 임베딩 Lambda가 그 사이에
    // 커밋하면 unclassified와 clusters가 서로 다른 시점을 말하게 된다. 읽기 전용이라
    // 직렬화 실패 위험 없이 스냅샷 하나로 묶을 수 있다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun cluster(galleryId: Long, userId: Long, level: Int?): PhotoClustersResponse {
        // 담당 작가와 초대받은 부부 양쪽이 볼 수 있어야 한다. 작가는 어떻게 묶이는지 확인해야
        // 하고, 고르는 것은 부부의 일이다.
        galleryAccessPolicy.requirePhotographerOrCouple(galleryId, userId)

        val selectedLevel = level ?: properties.defaultLevel
        // 컨트롤러 검증에만 맡기지 않는다. 이 서비스를 다른 곳에서 부르면 그 검증이 돌지 않고,
        // 정의되지 않은 레벨은 대응하는 파라미터 번들이 없다.
        val bundle = properties.levels[selectedLevel]
            ?: throw ClusterException(ClusterErrorCode.INVALID_LEVEL)

        val unclassified = photoRepository.countByGalleryIdAndEmbeddingIsNull(galleryId)
        val members = photoRepository.findAllByGalleryIdAndEmbeddingIsNotNullOrderByDisplayOrderAscIdAsc(galleryId)
        if (members.isEmpty()) {
            return PhotoClustersResponse(level = selectedLevel, clusters = emptyList(), unclassified = unclassified)
        }

        return PhotoClustersResponse(
            level = selectedLevel,
            clusters = groupBySimilarity(galleryId, members, bundle),
            unclassified = unclassified,
        )
    }

    private fun groupBySimilarity(
        galleryId: Long,
        members: List<Photo>,
        bundle: ClusterProperties.ClusterLevel,
    ): List<PhotoClusterResponse> {
        val indexById = members.withIndex().associate { (index, photo) -> photo.requiredId to index }
        val unionFind = UnionFind(members.size)

        // pgvector의 <=> 는 코사인 '거리'다. 유사도 0.9 = 거리 0.1.
        photoSimilarityRepository.findSimilarPairs(
            galleryId = galleryId,
            strictDistance = 1.0 - bundle.strictThreshold,
            lenientDistance = 1.0 - bundle.lenientThreshold,
            windowSeconds = bundle.windowSeconds,
        )
            .forEach { (left, right) ->
                // 질의는 갤러리 전체를 보지만 members는 임베딩이 있는 것만 담는다. 사이에
                // 임베딩이 지워지는 경우가 없으므로 양쪽이 어긋날 일은 없지만, 없는 id가
                // 오면 그 간선만 버린다 -- 전체 조회를 예외로 무너뜨릴 이유가 없다.
                val leftIndex = indexById[left] ?: return@forEach
                val rightIndex = indexById[right] ?: return@forEach
                unionFind.union(leftIndex, rightIndex)
            }

        return members.indices
            .groupBy { unionFind.find(it) }
            .values
            // 큰 묶음이 먼저 온다. 훑는 양을 줄이는 것이 목적이라 많이 묶인 것부터 보여야 한다.
            // 크기가 같으면 노출 순서가 앞선 쪽이 먼저다 -- 같은 요청을 두 번 보내면 같은
            // 순서가 나와야 프론트가 목록을 안정적으로 그린다.
            .sortedWith(compareByDescending<List<Int>> { it.size }.thenBy { it.first() })
            .map { indices -> PhotoClusterResponse.of(photoViewAssembler.toResponses(indices.map { members[it] })) }
    }
}
