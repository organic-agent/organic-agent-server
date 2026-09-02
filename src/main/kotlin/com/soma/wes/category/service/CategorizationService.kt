package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationJobPhoto
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.PhotoCategoryAssignment
import com.soma.wes.category.dto.response.CategorizationJobResponse
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.CategorizationJobPhotoRepository
import com.soma.wes.category.repository.CategorizationJobRepository
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.cluster.config.ClusterProperties
import com.soma.wes.cluster.repository.PhotoSimilarityRepository
import com.soma.wes.cluster.support.UnionFind
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.math.sqrt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CategorizationService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val photoRepository: PhotoRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val jobRepository: CategorizationJobRepository,
    private val jobPhotoRepository: CategorizationJobPhotoRepository,
    private val similarityRepository: PhotoSimilarityRepository,
    private val clusterProperties: ClusterProperties,
    private val clock: Clock,
) {
    @Transactional
    fun run(galleryId: Long, userId: Long): CategorizationJobResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        return runLocked(galleryId)
    }

    /** 최고 관리자 워크플로가 사용자 신원을 가장하지 않고 같은 분류 규칙을 실행하는 진입점. */
    @Transactional
    fun runAsAdmin(galleryId: Long): CategorizationJobResponse = runLocked(galleryId)

    private fun runLocked(galleryId: Long): CategorizationJobResponse {
        galleryRepository.requireWithLockById(galleryId)

        val initialCompleted = jobRepository.existsByGalleryIdAndModeAndStatus(
            galleryId,
            CategorizationMode.INITIAL,
            CategorizationStatus.SUCCEEDED,
        )
        val mode = if (initialCompleted) CategorizationMode.INCREMENTAL else CategorizationMode.INITIAL
        val allEmbedded = photoRepository.findAllByGalleryIdAndEmbeddingIsNotNullOrderByDisplayOrderAscIdAsc(galleryId)
        val processedIds = jobPhotoRepository.findAllByPhotoIdIn(allEmbedded.map { it.requiredId })
            .mapTo(mutableSetOf()) { it.photoId }
        val candidates = if (mode == CategorizationMode.INITIAL) allEmbedded else allEmbedded.filter { it.requiredId !in processedIds }

        val now = ZonedDateTime.now(clock)
        val job = jobRepository.save(CategorizationJob(galleryId, mode).also { it.startedAt = now })
        if (mode == CategorizationMode.INITIAL) {
            createInitialFolders(galleryId, candidates, now)
        } else {
            categorizeIncrementally(galleryId, candidates, now)
        }
        jobPhotoRepository.saveAll(candidates.map { CategorizationJobPhoto(job.requiredId, it.requiredId) })
        job.complete(ZonedDateTime.now(clock))

        return CategorizationJobResponse.of(job, candidates.size)
    }

    @Transactional(readOnly = true)
    fun latest(galleryId: Long, userId: Long): CategorizationJobResponse? {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        val job = jobRepository.findFirstByGalleryIdOrderByCreatedAtDesc(galleryId) ?: return null
        val count = jobPhotoRepository.findAll().count { it.jobId == job.requiredId }
        return CategorizationJobResponse.of(job, count)
    }

    private fun createInitialFolders(galleryId: Long, candidates: List<Photo>, at: ZonedDateTime) {
        if (candidates.isEmpty()) return
        val candidateIndex = candidates.withIndex().associate { it.value.requiredId to it.index }
        val unionFind = UnionFind(candidates.size)
        val level = clusterProperties.levels.getValue(clusterProperties.defaultLevel)
        similarityRepository.findSimilarPairs(
            galleryId,
            strictDistance = 1.0 - level.strictThreshold,
            lenientDistance = 1.0 - level.lenientThreshold,
            windowSeconds = level.windowSeconds,
        ).forEach { edge ->
            val left = candidateIndex[edge.leftId] ?: return@forEach
            val right = candidateIndex[edge.rightId] ?: return@forEach
            unionFind.union(left, right)
        }

        candidates.indices.groupBy { unionFind.find(it) }.values.forEachIndexed { index, indices ->
            val concept = conceptRepository.save(
                ConceptFolder(galleryId, "컨셉 ${index + 1}", index, CategorySource.AI),
            )
            val detail = detailRepository.save(
                DetailFolder(concept.requiredId, "세부 ${index + 1}", 0, CategorySource.AI),
            )
            assignmentRepository.saveAll(
                indices.map { photoIndex ->
                    PhotoCategoryAssignment(
                        candidates[photoIndex].requiredId,
                        detail.requiredId,
                        null,
                        CategorySource.AI,
                        null,
                        at,
                    )
                },
            )
        }
    }

    private fun categorizeIncrementally(galleryId: Long, candidates: List<Photo>, at: ZonedDateTime) {
        if (candidates.isEmpty()) return
        val existingPhotos = photoRepository.findAllByGalleryIdAndEmbeddingIsNotNullOrderByDisplayOrderAscIdAsc(galleryId)
        val assignments = assignmentRepository.findAllByPhotoIdIn(existingPhotos.map { it.requiredId })
            .associateBy { it.photoId }
        val assignedPhotos = existingPhotos.filter { it.requiredId in assignments }
        val threshold = clusterProperties.levels.getValue(clusterProperties.defaultLevel).strictThreshold
        var nextConceptOrder = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId).size

        candidates.forEach { candidate ->
            val nearest = assignedPhotos.maxByOrNull { cosine(candidate.embedding, it.embedding) }
            val similarity = nearest?.let { cosine(candidate.embedding, it.embedding) }
            val targetDetailId = if (nearest != null && similarity != null && similarity >= threshold) {
                assignments.getValue(nearest.requiredId).detailFolderId
            } else {
                val concept = conceptRepository.save(
                    ConceptFolder(galleryId, "컨셉 ${nextConceptOrder + 1}", nextConceptOrder++, CategorySource.AI),
                )
                detailRepository.save(
                    DetailFolder(concept.requiredId, "세부 1", 0, CategorySource.AI),
                ).requiredId
            }
            assignmentRepository.save(
                PhotoCategoryAssignment(candidate.requiredId, targetDetailId, null, CategorySource.AI, similarity, at),
            )
        }
    }

    private fun cosine(left: FloatArray?, right: FloatArray?): Double {
        if (left == null || right == null || left.size != right.size) return -1.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return -1.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }
}
