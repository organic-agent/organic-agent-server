package com.soma.wes.folder.support

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.analysis.domain.AnalysisJobEventType
import com.soma.wes.analysis.support.AnalysisJobEventRecorder
import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.folder.config.FolderProperties
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.domain.DetailFolder
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.dto.DetailFolderPlanDto
import com.soma.wes.folder.dto.PhotoAnalysisGroupingDto
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentBulkRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.projection.PhotoPlacement
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 최신 AI 컨셉 배정을 폴더로 물질화한다. 아직 폴더에 없는 사진만 넣는다 — 이미 있는 폴더의 이름·순서·배정은 건드리지 않는다.
 *
 * 새 사진의 자리는 [AiFolderPlanner]가 정한다: 같은 그룹·같은 세부 이름의 옛 사진이 든 기존 폴더 → 기존 컨셉 폴더 아래 새 세부 폴더 →
 * 새 컨셉 폴더. 새 폴더를 이룰 만큼 모이지 않은 사진은 미분류로 남는다.
 */
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
    private val properties: FolderProperties,
    private val clock: Clock,
    private val activityRecorder: ActivityRecorder,
    private val eventRecorder: AnalysisJobEventRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이번 호출이 사진을 넣은 폴더만 돌려준다 — 컨셉 폴더마다 사진이 들어간 세부 폴더와 그 사진들이다(이미 들어 있던 사진은 싣지 않는다).
     * 그래서 호출자가 응답으로 "이번에 몇 장을 몇 폴더에 넣었나"를 셀 수 있다. 같은 잡을 이미 물질화했으면 그 결과를 다시 돌려준다(멱등).
     */
    @Transactional
    fun materialize(galleryId: Long): List<ConceptFolderResponse> {
        val startedAt = clock.millis()
        galleryRepository.requireWithLockById(galleryId)

        // 1. 최신 컨셉 배정(= 가장 최근 분석 잡의 결과)을 읽는다. 컨셉 배정은 특정 job 에서 추출한 {컨셉 - 컨셉 디테일 - 임베딩 그룹(디테일 폴더 대상) Id} 의 묶음이다.
        //    이 잡을 이미 물질화했으면 다시 넣지 않고 그 결과를 돌려준다. 표식은 배정 행의 잡 번호다 — 기존 폴더에 합치기만 한 잡은
        //    컨셉 폴더를 만들지 않아 폴더로는 알아볼 수 없다. 컨셉 폴더의 잡 번호는 배정 행에 번호가 없던 시절의 세트를 위한 것이다.
        val latest = conceptAssignmentLoader.loadLatest(galleryId)
            ?: throw FolderException(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
        val alreadyPlaced = assignmentRepository.findAllPlacementsByAnalysisJobId(latest.analysisJobId)
        if (alreadyPlaced.isNotEmpty()) return materializedResponses(galleryId, alreadyPlaced)
        val existingSet = conceptRepository.findAllByGalleryIdAndAnalysisJobIdOrderBySortOrderAscIdAsc(galleryId, latest.analysisJobId)
        if (existingSet.isNotEmpty()) return viewAssembler.toResponses(existingSet)

        // 2. 분류가 끝난 사진 전부와 지금의 폴더 구조를 읽어 계획을 세운다. 계획은 아직 폴더에 없는 사진만 다룬다.
        val photos = loadPhotoGroupings(galleryId)
        val detailFolderIdByPhotoId = assignmentRepository.findAllPlacementsByGalleryId(galleryId)
            .associate { it.photoId to it.detailFolderId }
        if (photos.none { it.photoId !in detailFolderIdByPhotoId }) throw FolderException(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)
        val concepts = conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
        val details = detailRepository.findAllByConceptFolderIdIn(concepts.map { it.requiredId })
        val plan = planner.plan(
            assignments = latest.assignments,
            photos = photos,
            detailFolderIdByPhotoId = detailFolderIdByPhotoId,
            conceptFolderIdByDetailFolderId = details.associate { it.requiredId to it.conceptFolderId },
            // 같은 이름이 여럿이면 화면 순서상 앞의 폴더로 — 뒤에서부터 넣어 앞의 것이 남게 한다.
            conceptFolderIdByName = concepts.asReversed().associate { it.name to it.requiredId },
            minNewDetailPhotos = properties.minNewDetailPhotos,
            minOldShareForDetailMerge = properties.minOldShareForDetailMerge,
        )
        // 넣을 곳이 정해진 사진이 없다 — 전부 미분류로 남는 소수다. 할 일이 없는 것이다.
        if (plan.isEmpty) throw FolderException(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)

        // 3. 계획대로 저장한다. 배정은 세부 폴더마다 JDBC 배치로 넣고, 응답은 방금 넣은 것으로 바로 만든다 — 수천 행을 다시 읽지 않는다.
        val assignedAt = ZonedDateTime.now(clock)
        val detailById = details.associateBy { it.requiredId }

        // 3-1. 기존 세부 폴더에 합친다.
        val mergedDetailsByConceptId = plan.merges.entries
            .map { (detailFolderId, photoIds) ->
                val detail = detailById.getValue(detailFolderId)
                assignmentBulkRepository.insertAiAssignments(galleryId, detailFolderId, photoIds, assignedAt, latest.analysisJobId)
                detail.conceptFolderId to DetailFolderResponse.of(detail, photoIds)
            }
            .groupBy({ (conceptFolderId, _) -> conceptFolderId }, { (_, response) -> response })

        // 3-2. 기존 컨셉 폴더 아래에 새 세부 폴더를 만든다. 그 컨셉의 기존 세부 폴더 맨 뒤에 붙인다.
        val newDetailsByConceptId = plan.newDetails.mapValues { (conceptFolderId, detailPlans) ->
            val firstSortOrder = detailRepository.findNextSortOrderByConceptFolderId(conceptFolderId)
            detailPlans.mapIndexed { index, detailPlan -> saveDetail(galleryId, conceptFolderId, firstSortOrder + index, detailPlan, assignedAt, latest.analysisJobId) }
        }

        // 3-3. 새 컨셉 폴더는 기존 폴더 맨 뒤에 붙고, 이번 잡 id를 달아 다음 호출의 1번 멱등 검사에 걸리게 한다.
        val firstSortOrder = conceptRepository.findNextSortOrderByGalleryId(galleryId)
        val newConceptResponses = plan.newConcepts.mapIndexed { conceptIndex, conceptPlan ->
            val concept = conceptRepository.save(
                ConceptFolder(
                    galleryId = galleryId,
                    name = conceptPlan.name,
                    sortOrder = firstSortOrder + conceptIndex,
                    createdSource = FolderSource.AI,
                    analysisJobId = latest.analysisJobId,
                ),
            )
            val newDetails = conceptPlan.details.mapIndexed { detailIndex, detailPlan ->
                saveDetail(galleryId, concept.requiredId, detailIndex, detailPlan, assignedAt, latest.analysisJobId)
            }
            ConceptFolderResponse.of(concept, newDetails)
        }

        // 4. 응답 — 손댄 기존 컨셉 폴더(화면 순서), 그 뒤에 새 컨셉 폴더.
        val touchedExisting = concepts
            .filter { it.requiredId in mergedDetailsByConceptId || it.requiredId in newDetailsByConceptId }
            .map { concept ->
                ConceptFolderResponse.of(
                    concept,
                    mergedDetailsByConceptId[concept.requiredId].orEmpty() + newDetailsByConceptId[concept.requiredId].orEmpty(),
                )
            }

        activityRecorder.recordGallery(galleryId)
        log.info(
            "event=folder.materialized gallery={} job={} merged={} mergedDetails={} newDetails={} newConcepts={} assigned={} leftUnclassified={} elapsedMs={}",
            galleryId, latest.analysisJobId,
            plan.merges.values.sumOf { it.size }, plan.merges.size,
            plan.newDetails.values.sumOf { it.size } + plan.newConcepts.sumOf { it.details.size }, plan.newConcepts.size,
            (touchedExisting + newConceptResponses).sumOf { concept -> concept.details.sumOf { it.photoIds.size } },
            plan.leftUnclassified.size, clock.millis() - startedAt,
        )
        eventRecorder.record(
            latest.analysisJobId, galleryId, AnalysisJobEventType.FOLDER_MATERIALIZED,
            mapOf(
                "merged" to plan.merges.values.sumOf { it.size },
                "mergedDetails" to plan.merges.size,
                "newDetails" to plan.newDetails.values.sumOf { it.size } + plan.newConcepts.sumOf { it.details.size },
                "newConcepts" to plan.newConcepts.size,
                "leftUnclassified" to plan.leftUnclassified.size,
                "elapsedMs" to clock.millis() - startedAt,
            ),
        )
        return touchedExisting + newConceptResponses
    }

    /**
     * 잡이 넣은 사진을 지금 든 세부 폴더별로 묶어 첫 호출과 같은 모양으로 돌려준다. 그 뒤 사용자가 옮긴 사진은 옮겨진 폴더에 실린다.
     */
    private fun materializedResponses(galleryId: Long, placements: List<PhotoPlacement>): List<ConceptFolderResponse> {
        val photoIdsByDetailFolderId = placements.groupBy({ it.detailFolderId }, { it.photoId })
        val detailsByConceptFolderId = detailRepository.findAllById(photoIdsByDetailFolderId.keys)
            .sortedWith(compareBy({ it.sortOrder }, { it.requiredId }))
            .groupBy({ it.conceptFolderId }, { DetailFolderResponse.of(it, photoIdsByDetailFolderId.getValue(it.requiredId)) })
        return conceptRepository.findAllByGalleryIdOrderBySortOrderAscIdAsc(galleryId)
            .filter { it.requiredId in detailsByConceptFolderId }
            .map { ConceptFolderResponse.of(it, detailsByConceptFolderId.getValue(it.requiredId)) }
    }

    private fun saveDetail(
        galleryId: Long,
        conceptFolderId: Long,
        sortOrder: Int,
        plan: DetailFolderPlanDto,
        assignedAt: ZonedDateTime,
        analysisJobId: Long,
    ): DetailFolderResponse {
        val detail = detailRepository.save(
            DetailFolder(
                galleryId = galleryId,
                conceptFolderId = conceptFolderId,
                name = plan.name,
                sortOrder = sortOrder,
                createdSource = FolderSource.AI,
            ),
        )
        assignmentBulkRepository.insertAiAssignments(galleryId, detail.requiredId, plan.photoIds, assignedAt, analysisJobId)
        return DetailFolderResponse.of(detail, plan.photoIds)
    }

    /**
     * 폴더 계획의 재료 — categorize 까지 끝난 사진을 화면 순서로. 벡터를 빼고 그룹·피사체·연사만 읽는다.
     * 분류가 덜 끝난 사진(잡이 categorize 를 보낸 뒤에 올라온 사진)은 여기 없으므로 폴더에 들어가지 않고 다음 잡을 기다린다.
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
