package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationJob
import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.domain.CategorySource
import com.soma.wes.category.domain.ConceptFolder
import com.soma.wes.category.domain.DetailFolder
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.dto.response.DetailFolderResponse
import com.soma.wes.category.exception.CategoryErrorCode
import com.soma.wes.category.exception.CategoryException
import com.soma.wes.category.domain.CategorizationPhotoStatus
import com.soma.wes.category.repository.CategoryBulkWriter
import com.soma.wes.category.repository.CategorizationJobPhotoRepository
import com.soma.wes.category.repository.CategorizationJobRepository
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.repository.DetailFolderRepository
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.category.support.AiCategoryFolderPlanner
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.analysis.support.AiConceptAssignmentLoader
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 최신 AI 컨셉 배정을 최종 카테고리 ERD에 물질화한다.
 *
 * 갤러리 락을 쥔 동기 트랜잭션 하나다. 그래서 큰 갤러리에서도 수 초 안에 끝나야 한다 — 7천 장에서 4분이 걸려
 * ALB(60초)가 먼저 끊고 재시도가 락에 줄을 섰던 일(#160)이 이 클래스의 읽기·적재·응답 형태를 정했다:
 * 분석 행은 벡터 없는 프로젝션으로, 배정·잡 사진 행은 JDBC 배치로, 응답은 방금 만든 것을 다시 읽지 않고 메모리에서.
 */
@Service
class AiCategoryFolderService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
    private val categorizationJobRepository: CategorizationJobRepository,
    private val categorizationJobPhotoRepository: CategorizationJobPhotoRepository,
    private val bulkWriter: CategoryBulkWriter,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val aiConceptAssignmentLoader: AiConceptAssignmentLoader,
    private val planner: AiCategoryFolderPlanner,
    private val clock: Clock,
) {

    @Transactional
    fun createFromAnalysis(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireUploader(galleryId, userId)
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
        val processedIds = categorizationJobPhotoRepository.findAllPhotoIdsByGalleryId(galleryId).toSet()
        val assignedIds = assignmentRepository.findAllPhotoIdsByGalleryId(galleryId).toSet()
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
        val firstSortOrder = conceptRepository.countByGalleryId(galleryId).toInt()
        val assignedAt = ZonedDateTime.now(clock)
        // 응답은 여기서 만든 것으로 바로 조립한다 — 방금 INSERT한 세부 폴더·배정 수천 행을 다시 SELECT하지 않는다.
        val responses = plans.mapIndexed { conceptIndex, plan ->
            val concept = conceptRepository.save(
                ConceptFolder(
                    galleryId = galleryId,
                    name = plan.name,
                    sortOrder = firstSortOrder + conceptIndex,
                    createdSource = CategorySource.AI,
                    analysisJobId = latest.jobId,
                ),
            )
            val details = plan.details.mapIndexed { detailIndex, detailPlan ->
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
                bulkWriter.insertAiAssignments(galleryId, detail.requiredId, detailPlan.photoIds, assignedAt)
                detailResponse(detail, detailPlan.photoIds)
            }
            ConceptFolderResponse.of(concept, details)
        }

        // 계획은 모든 대상 사진을 어느 세부 폴더엔가 넣는다("기타" 포함). 그래서 배정된 사진 = 계획에 든 사진이다.
        val assignedPhotoIds = plans.flatMapTo(mutableSetOf()) { plan -> plan.details.flatMap { it.photoIds } }
        recordCategorization(galleryId, members.map { it.photoId }, assignedPhotoIds)
        return responses
    }

    /**
     * 폴더 계획의 재료 — 분석 행이 있는 사진 전부를 화면 순서로. 벡터를 빼고 그룹·피사체·연사만 읽는다.
     * 분석이 안 끝난 사진(그룹 null)도 포함해 "기타"로 보낸다 — 예전 엔티티 읽기와 같은 범위다.
     */
    private fun loadMembers(galleryId: Long): List<AiCategoryFolderPlanner.MemberPhoto> =
        photoAnalysisRepository.findAllGroupingByGalleryIdOrderByDisplay(galleryId).map {
            AiCategoryFolderPlanner.MemberPhoto(
                photoId = it.photoId,
                embedGroupId = it.embedGroupId,
                subjects = it.subjects,
                clusterId = it.clusterId,
            )
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
        bulkWriter.insertJobPhotos(
            galleryId,
            job.requiredId,
            photoIds.associateWith { if (it in assignedPhotoIds) CategorizationPhotoStatus.ASSIGNED else CategorizationPhotoStatus.UNCLASSIFIED },
            completedAt,
        )
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
                    .map { detail -> detailResponse(detail, photoIdsByDetail[detail.requiredId].orEmpty()) },
            )
        }
    }

    private fun detailResponse(detail: DetailFolder, photoIds: List<Long>) = DetailFolderResponse(
        id = detail.requiredId,
        galleryId = detail.galleryId,
        conceptFolderId = detail.conceptFolderId,
        name = detail.name,
        sortOrder = detail.sortOrder,
        createdSource = detail.createdSource,
        category = detail.category,
        needsReview = detail.needsReview,
        photoIds = photoIds,
    )
}
