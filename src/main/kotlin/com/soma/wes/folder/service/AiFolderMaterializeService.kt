package com.soma.wes.folder.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.dto.ConceptAssignmentDto
import com.soma.wes.folder.dto.MemberPhotoDto
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderAssignmentBulkRepository
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.PhotoFolderAssignmentRepository
import com.soma.wes.folder.support.AiFolderPlanner
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// [REFACTOR-RENAME 2026-09-27] AiFolderService → AiFolderMaterializeService (클래스 이름만 변경, 동작 동일)
/**
 * 최신 AI 컨셉 배정을 최종 카테고리 ERD에 물질화한다.
 *
 * 갤러리 락을 쥔 동기 트랜잭션 하나다. 그래서 큰 갤러리에서도 수 초 안에 끝나야 한다 — 7천 장에서 4분이 걸려
 * ALB(60초)가 먼저 끊고 재시도가 락에 줄을 섰던 일(#160)이 이 클래스의 읽기·적재·응답 형태를 정했다:
 * 분석 행은 벡터 없는 프로젝션으로, 배정 행은 JDBC 배치로, 응답은 방금 만든 것을 다시 읽지 않고 메모리에서.
 *
 * 진입점은 둘이다 — 작가의 버튼([createFromAnalysis], 인가 있음)과 인가를 이미 마친 호출자를 위한 [materializeFromAnalysis]
 * (분석 잡의 자동 물질화, 부부의 폴더 확정, 관리자 워크플로). 관리자 전용 경로는 없다.
 */
@Service
class AiFolderMaterializeService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: PhotoFolderAssignmentRepository,
    private val assignmentBulkRepository: PhotoFolderAssignmentBulkRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val conceptAssignmentLoader: ConceptAssignmentLoader,
    private val planner: AiFolderPlanner,
    private val viewAssembler: FolderViewAssembler,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {

    @Transactional
    fun createFromAnalysis(galleryId: Long, userId: Long): List<ConceptFolderResponse> {
        galleryAccessPolicy.requireUploader(galleryId, userId)
        return createFromAnalysisLocked(galleryId)
    }

    /**
     * 인가 없는 시스템 진입점 — 호출자가 이미 인가를 마쳤거나 사용자 신원이 없는 경우(분석 잡 스윕의 자동 물질화,
     * 부부의 폴더 확정 `FolderConfirmationService`, 관리자 워크플로). 같은 잡의 세트가 이미 있으면 그것을 돌려준다(멱등).
     */
    @Transactional
    fun materializeFromAnalysis(galleryId: Long): List<ConceptFolderResponse> =
        createFromAnalysisLocked(galleryId)

    private fun createFromAnalysisLocked(galleryId: Long): List<ConceptFolderResponse> {
        galleryRepository.requireWithLockById(galleryId)

        val latest = conceptAssignmentLoader.loadLatest(galleryId)
            ?: throw FolderException(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
        val existingSet = conceptRepository
            .findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(galleryId, latest.jobId)
        if (existingSet.isNotEmpty()) return viewAssembler.toResponses(existingSet)

        // 이미 폴더에 든 사진(사용자가 옮긴 것 포함)은 다시 배정하지 않는다 — 재물질화가 USER 배정을 건드리지 않는 이유가 이 한 줄이다.
        val allMembers = loadMembers(galleryId)
        val assignedIds = assignmentRepository.findAllPhotoIdsByGalleryId(galleryId).toSet()
        val members = allMembers.filter { it.photoId !in assignedIds }
        if (members.isEmpty()) throw FolderException(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)

        val plans = planner.plan(
            assignments = latest.assignments.map {
                // [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner.GroupAssignment → GroupAssignmentDto
                ConceptAssignmentDto(
                    embedGroupId = it.embedGroupId,
                    conceptName = it.conceptName,
                    detailName = it.detailName,
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
                    createdSource = FolderSource.AI,
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
                        createdSource = FolderSource.AI,
                        cutType = detailPlan.cutType,
                        needsReview = detailPlan.needsReview,
                    ),
                )
                assignmentBulkRepository.insertAiAssignments(galleryId, detail.requiredId, detailPlan.photoIds, assignedAt)
                // [REFACTOR-A 2026-09-27] private detailResponse(...) → DetailFolderResponse.of. 메모리 조립은 그대로(#160).
                DetailFolderResponse.of(detail, detailPlan.photoIds)
            }
            ConceptFolderResponse.of(concept, details)
        }

        activityRecorder.recordGallery(galleryId)
        return responses
    }

    /**
     * 폴더 계획의 재료 — 분석 행이 있는 사진 전부를 화면 순서로. 벡터를 빼고 그룹·피사체·연사만 읽는다.
     * 분석이 안 끝난 사진(그룹 null)도 포함해 "기타"로 보낸다 — 예전 엔티티 읽기와 같은 범위다.
     */
    // [REFACTOR-PLANNER-DTO 2026-09-27] AiFolderPlanner.MemberPhoto → MemberPhotoDto
    private fun loadMembers(galleryId: Long): List<MemberPhotoDto> =
        photoAnalysisRepository.findAllGroupingByGalleryIdOrderByDisplay(galleryId).map {
            MemberPhotoDto(
                photoId = it.photoId,
                embedGroupId = it.embedGroupId,
                subjects = it.subjects,
                burstId = it.burstId,
            )
        }
}
