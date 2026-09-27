package com.soma.wes.folder.support

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.dto.PhotoAnalysisGroupingDto
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentBulkRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/** 최신 AI 컨셉 배정을 최종 카테고리 ERD에 물질화한다. */
@Component
class AiFolderMaterializer(
    private val galleryRepository: GalleryRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val detailRepository: DetailFolderRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val assignmentBulkRepository: DetailFolderAssignmentBulkRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val conceptAssignmentLoader: ConceptAssignmentLoader,
    private val planner: AiFolderPlanner,
    private val viewAssembler: FolderViewAssembler,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
) {

    /** 같은 잡의 세트가 이미 있으면 그것을 돌려준다(멱등). */
    @Transactional
    fun materialize(galleryId: Long): List<ConceptFolderResponse> {
        galleryRepository.requireWithLockById(galleryId)

        // 1. 최신 컨셉 배정(= 가장 최근 분석 잡의 결과)을 읽는다. 컨셉 배정은 특정 job 에서 추출한 {컨셉 - 컨셉 디테일 - 임베딩 그룹(디테일 폴더 대상) Id} 의 묶음이다.
        //    폴더 세트는 분석 잡 하나당 하나라,  이 잡으로 만든 세트가 이미 있으면 새로 만들지 않고 그대로 돌려준다.
        //    사진을 더 올려도 새 분석 잡이 끝나기 전까지는 최신 잡이 그대로라 여기서 끝난다.
        val latest = conceptAssignmentLoader.loadLatest(galleryId)
            ?: throw FolderException(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
        val existingSet = conceptRepository.findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(galleryId, latest.analysisJobId)
        if (existingSet.isNotEmpty()) return viewAssembler.toResponses(existingSet)

        // 2. 새 분석 잡이면 세트가 아직 없다. 이전 잡의 세트에 이미 든 사진(과 사용자가 옮긴 사진)은 빼고,
        //    남은 사진과 이번 잡의 배정으로 폴더 계획을 세운다.
        val unassignedPhotos = loadUnassignedPhotos(galleryId)
        val plans = planner.plan(
            assignments = latest.assignments,
            photos = unassignedPhotos,
        )

        // 3. 계획대로 컨셉 폴더 → 세부 폴더 → 사진 배정 순으로 저장한다(뒤 행이 앞 행의 id를 FK로 쓴다).
        //    새 컨셉 폴더는 기존 폴더 맨 뒤에 붙고, 이번 잡 id를 달아 다음 호출의 1번 멱등 검사에 걸리게 한다.
        //    배정은 세부 폴더마다 JDBC 배치로 넣고, 응답은 방금 저장한 것으로 바로 만든다 — 수천 행을 다시 읽지 않는다
        val firstSortOrder = conceptRepository.countByGalleryId(galleryId).toInt()
        val assignedAt = ZonedDateTime.now(clock)
        val responses = plans.mapIndexed { conceptIndex, plan ->
            // 컨셉 폴더 저장
            val concept = conceptRepository.save(
                ConceptFolder(
                    galleryId = galleryId,
                    name = plan.name,
                    sortOrder = firstSortOrder + conceptIndex,
                    createdSource = FolderSource.AI,
                    analysisJobId = latest.analysisJobId,
                ),
            )
            // 컨셉 폴더 - 디테일 컨셉 폴더 저장
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
                // 디테일 폴더 - 사진 저장
                assignmentBulkRepository.insertAiAssignments(galleryId, detail.requiredId, detailPlan.photoIds, assignedAt)
                DetailFolderResponse.of(detail, detailPlan.photoIds)
            }
            ConceptFolderResponse.of(concept, details)
        }

        // 해당 작업 기록
        activityRecorder.recordGallery(galleryId)
        return responses
    }

    /** 이미 폴더에 든 사진(이전 세트·사용자가 옮긴 사진)은 건드리지 않도록, 아직 어느 세부 폴더에도 없는 사진만 돌려준다. */
    private fun loadUnassignedPhotos(galleryId: Long): List<PhotoAnalysisGroupingDto> {
        val assignedIds = assignmentRepository.findAllPhotoIdsByGalleryId(galleryId).toSet()
        val unassignedPhotos = loadPhotoGroupings(galleryId).filter { it.photoId !in assignedIds }
        if (unassignedPhotos.isEmpty()) throw FolderException(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)
        return unassignedPhotos
    }

    /**
     * 폴더 계획의 재료 — 분석 행이 있는 사진 전부를 화면 순서로. 벡터를 빼고 그룹·피사체·연사만 읽는다.
     */
    private fun loadPhotoGroupings(galleryId: Long): List<PhotoAnalysisGroupingDto> =
        photoAnalysisRepository.findAllGroupingByGalleryIdOrderByDisplay(galleryId).map {
            PhotoAnalysisGroupingDto(
                photoId = it.photoId,
                embedGroupId = it.embedGroupId,
                subjects = it.subjects,
                burstId = it.burstId,
            )
        }
}
