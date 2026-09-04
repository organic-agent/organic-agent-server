package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationJobPhoto
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.domain.PhotoCategoryAssignment
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.dto.response.DetailFolderResponse
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.repository.CategorizationJobPhotoRepository
import com.soma.wes.category.repository.CategorizationJobRepository
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.category.support.AiCategoryFolderPlanner
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.analysis.support.AiConceptAssignmentLoader
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 최신 AI 컨셉 배정을 최종 카테고리 ERD에 물질화한다. */
@Service
class AiCategoryFolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val categorizationJobRepository: CategorizationJobRepository,
    private val categorizationJobPhotoRepository: CategorizationJobPhotoRepository,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val aiConceptAssignmentLoader: AiConceptAssignmentLoader,
    private val planner: AiCategoryFolderPlanner,
    private val clock: Clock,
) {

    @Transactional
    fun createFromAnalysis(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireManager(galleryId, userId)
        return createFromAnalysisLocked(galleryId)
    }

    /** 관리자 API가 사용자 신원을 가장하지 않고 같은 분석 결과를 물질화하는 진입점. */
    @Transactional
    fun createFromAnalysisAsAdmin(galleryId: Long): List<ConceptFolderResponse> =
        createFromAnalysisLocked(galleryId)

    private fun createFromAnalysisLocked(galleryId: Long): List<ConceptFolderResponse> {
        galleryRepository.requireWithLockById(galleryId)

        val latest = aiConceptAssignmentLoader.loadLatest(galleryId)
            ?: throw CategoryException(CategoryErrorCode.ANALYSIS_NOT_COMPLETE)
        val existingSet = conceptRepository
            .findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(galleryId, latest.jobId)
        if (existingSet.isNotEmpty()) return responsesOf(existingSet)

        val allMembers = loadMembers(galleryId)
        val processedIds = categorizationJobPhotoRepository.findAllByPhotoIdIn(allMembers.map { it.photoId })
            .mapTo(mutableSetOf()) { it.photoId }
        val assignedIds = assignmentRepository.findAllByPhotoIdIn(allMembers.map { it.photoId })
            .mapTo(mutableSetOf()) { it.photoId }
        val members = allMembers.filter { it.photoId !in processedIds && it.photoId !in assignedIds }
        if (members.isEmpty()) throw CategoryException(CategoryErrorCode.NO_PHOTOS_TO_ORGANIZE)

        val plans = planner.plan(
            assignments = latest.assignments.map {
                AiCategoryFolderPlanner.GroupAssignment(
                    embedGroupId = it.embedGroupId,
                    parentName = it.parentName,
                    conceptName = it.conceptName,
                    needsReview = it.needsReview,
                )
            },
            members = members,
        )
        val firstSortOrder = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId).size
        val concepts = plans.mapIndexed { conceptIndex, plan ->
            val concept = conceptRepository.save(
                ConceptFolder(
                    galleryId = galleryId,
                    name = plan.name,
                    sortOrder = firstSortOrder + conceptIndex,
                    createdSource = CategorySource.AI,
                    analysisJobId = latest.jobId,
                ),
            )
            plan.details.forEachIndexed { detailIndex, detailPlan ->
                val detail = detailRepository.save(
                    DetailFolder(
                        galleryId = galleryId,
                        conceptFolderId = concept.requiredId,
                        name = detailPlan.name,
                        sortOrder = detailIndex,
                        createdSource = CategorySource.AI,
                        category = detailPlan.category,
                        needsReview = detailPlan.needsReview,
                    ),
                )
                assignmentRepository.saveAll(
                    detailPlan.photoIds.map { photoId ->
                        PhotoCategoryAssignment(
                            galleryId = galleryId,
                            photoId = photoId,
                            detailFolderId = detail.requiredId,
                            assignedByUserId = null,
                            assignedSource = CategorySource.AI,
                            confidence = null,
                            assignedAt = ZonedDateTime.now(clock),
                        )
                    },
                )
            }
            concept
        }

        val processedPhotoIds = members.map { it.photoId }
        val assignedPhotoIds = assignmentRepository.findAllByGalleryIdAndPhotoIdIn(galleryId, processedPhotoIds)
            .map { it.photoId }
            .toSet()
        recordCategorization(galleryId, processedPhotoIds, assignedPhotoIds)
        return responsesOf(concepts)
    }

    private fun loadMembers(galleryId: Long): List<AiCategoryFolderPlanner.MemberPhoto> {
        val analysisByPhotoId = photoAnalysisRepository.findAllByGalleryId(galleryId).associateBy { it.photoId }
        return photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(galleryId)
            .sortedWith(Photo.DISPLAY_ORDER)
            .mapNotNull { photo ->
                analysisByPhotoId[photo.requiredId]?.let { analysis ->
                    AiCategoryFolderPlanner.MemberPhoto(
                        photoId = photo.requiredId,
                        embedGroupId = analysis.embedGroupId,
                        subjects = analysis.subjects,
                        clusterId = analysis.clusterId,
                    )
                }
            }
    }

    private fun recordCategorization(galleryId: Long, photoIds: List<Long>, assignedPhotoIds: Set<Long>) {
        val initialCompleted = categorizationJobRepository.existsByGalleryIdAndModeAndStatus(
            galleryId,
            CategorizationMode.INITIAL,
            CategorizationStatus.SUCCEEDED,
        )
        val mode = if (initialCompleted) CategorizationMode.INCREMENTAL else CategorizationMode.INITIAL
        val now = ZonedDateTime.now(clock)
        val job = categorizationJobRepository.save(CategorizationJob(galleryId, mode).also { it.startedAt = now })
        val completedAt = ZonedDateTime.now(clock)
        categorizationJobPhotoRepository.saveAll(photoIds.map { photoId ->
            CategorizationJobPhoto(galleryId, job.requiredId, photoId).also { row ->
                if (photoId in assignedPhotoIds) row.assigned(completedAt) else row.unclassified(completedAt)
            }
        })
        job.complete(completedAt)
    }

    private fun responsesOf(concepts: List<ConceptFolder>): List<ConceptFolderResponse> {
        val details = detailRepository.findAllByConceptFolderIdIn(concepts.map { it.requiredId })
        val assignments = assignmentRepository.findAllByDetailFolderIdIn(details.map { it.requiredId })
        val photoIdsByDetail = assignments.groupBy { it.detailFolderId }
            .mapValues { (_, rows) -> rows.map { it.photoId } }
        val detailsByConcept = details.groupBy { it.conceptFolderId }
        return concepts.map { concept ->
            ConceptFolderResponse.of(
                concept,
                detailsByConcept[concept.requiredId].orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
                    .map { detail ->
                        DetailFolderResponse(
                            id = detail.requiredId,
                            galleryId = detail.galleryId,
                            conceptFolderId = detail.conceptFolderId,
                            name = detail.name,
                            sortOrder = detail.sortOrder,
                            createdSource = detail.createdSource,
                            category = detail.category,
                            needsReview = detail.needsReview,
                            photoIds = photoIdsByDetail[detail.requiredId].orEmpty(),
                        )
                    },
            )
        }
    }
}
