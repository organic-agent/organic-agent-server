package com.soma.wes.recommendation.service

import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.folder.support.AiFolderSetReader
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.recommendation.domain.AiJobStatus
import com.soma.wes.recommendation.domain.AiRecommendation
import com.soma.wes.recommendation.domain.AiSelectionJob
import com.soma.wes.recommendation.domain.AiSelectionMode
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.dto.response.AiRecommendationListResponse
import com.soma.wes.recommendation.dto.response.AiRecommendationResponse
import com.soma.wes.recommendation.dto.response.AiSelectionJobResponse
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.repository.AiRecommendationRepository
import com.soma.wes.recommendation.repository.AiSelectionJobRepository
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 폴더별 AI 추천을 요청하고 읽는다.
 *
 * 요청은 `ai_selection_jobs`에 PENDING 행을 넣고 커밋 뒤 실행기에 넘긴다 — 계산(폴더마다 목표 비례 n장,
 * 연사 클러스터당 1장, 이유 문장)은 [AiSelectionJobRunner]가 요청 스레드 밖에서 한다.
 *
 * 추천은 사진에 붙는다. 읽을 때는 라운드가 아니라 **사진마다 가장 최근 추천**에 사진·담김 여부·현재 폴더를
 * 붙여 돌려준다 — 사진을 다른 폴더로 옮겨도 표시가 따라가고, 폴더 하나만 다시 추천해도 다른 폴더의 표시는
 * 남는다. 잡의 범위(세부폴더 하나 또는 전체)에 든 사진의 기존 추천만 실행기가 지우고 다시 적는다.
 */
@Service
class AiRecommendationService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val aiFolderSetReader: AiFolderSetReader,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val aiSelectionJobRepository: AiSelectionJobRepository,
    private val aiRecommendationRepository: AiRecommendationRepository,
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: PhotoViewAssembler,
    private val properties: StorageProperties,
    private val jobLauncher: AiSelectionJobLauncher,
    private val activityRecorder: ActivityRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 추천 한 라운드를 요청한다. 셀렉의 주인인 부부만(`requireSelectionEditor`) — 마감·미공개 갤러리는
     * 거기서 막힌다. 아직 셀렉 행이 없으면 여기서 만든다(담기와 같은 규약).
     *
     * 추천은 AI 폴더 세트 위에서 돈다 — 세트가 없으면 409로 거절하고 폴백을 두지 않는다.
     * 제출된 앨범에는 걸지 않으며, 살아 있는 추천 잡이 있으면 거절한다. 두 검사 사이의 경쟁은
     * DB의 부분 유니크가 잡고, 그 위반을 같은 409로 번역한다.
     *
     * 모드는 셀렉의 이력으로 정한다 — 추천 라운드가 하나도 없으면 DRAFT, 있으면 REFINE(담기·거절
     * 반응을 빼고 다시 계산한 다음 라운드). 화면이 고를 이유가 없어 본문에서 받지 않는다.
     *
     * 범위(detailFolderId)를 주면 그 세부폴더만 다시 추천한다. 세트에 든 폴더가 아니어도(사용자가 만든
     * 폴더) 된다 — 그래서 폴더 범위 요청에는 AI 세트가 없어도 거절하지 않는다.
     */
    @Transactional
    fun request(galleryId: Long, userId: Long, request: AiRecommendationRequest): AiSelectionJobResponse {
        galleryAccessPolicy.requireSelectionEditor(galleryId, userId)

        if (request.prompt?.let { it.isBlank() || it.length > 1000 } == true ||
            request.targetCount?.let { it !in 1..500 } == true
        ) throw RecommendationException(RecommendationErrorCode.INVALID_QUERY)

        val detailFolderId = request.detailFolderId?.also { detailFolderId ->
            aiFolderSetReader.detailFolder(galleryId, detailFolderId)
                ?: throw RecommendationException(RecommendationErrorCode.DETAIL_FOLDER_NOT_FOUND)
        }
        val analysisJobId = resolveAnalysisJobId(galleryId, request.analysisJobId, required = detailFolderId == null)

        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: photoSelectionRepository.save(PhotoSelection(galleryId = galleryId))
        selection.requireEditable()
        val selectionId = selection.requiredId

        if (aiSelectionJobRepository.existsBySelectionIdAndStatusIn(selectionId, AiJobStatus.ACTIVE)) {
            throw RecommendationException(RecommendationErrorCode.SELECTION_JOB_ALREADY_ACTIVE)
        }

        val mode = when {
            aiRecommendationRepository.existsBySelectionId(selectionId) -> AiSelectionMode.REFINE
            else -> AiSelectionMode.DRAFT
        }
        val job = try {
            aiSelectionJobRepository.saveAndFlush(
                AiSelectionJob(
                    selectionId = selectionId,
                    mode = mode,
                    analysisJobId = analysisJobId,
                    detailFolderId = detailFolderId,
                    prompt = request.prompt?.trim(),
                    targetCount = request.targetCount,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw RecommendationException(RecommendationErrorCode.SELECTION_JOB_ALREADY_ACTIVE)
        }
        activityRecorder.recordGallery(galleryId)

        log.info(
            "AI 추천 요청: galleryId={}, selectionId={}, mode={}, analysisJobId={}, detailFolderId={}, jobId={}",
            galleryId, selectionId, mode, analysisJobId, detailFolderId, job.requiredId,
        )
        // 커밋 뒤에 넘긴다 — 실행기가 아직 안 보이는 행을 집으려다 실패하면 안 된다.
        val jobId = job.requiredId
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = jobLauncher.launch(jobId)
            },
        )

        return AiSelectionJobResponse.from(job)
    }

    /**
     * 콕 집은 세트는 살아 있어야 하고(404), 생략하면 최신 세트다. 전체 라운드([required])인데 세트 자체가
     * 없으면 409 — 폴더 생성이 먼저다. 폴더 범위 요청은 세트 없이도 돌므로 null을 허용한다.
     */
    // [GLOSSARY-1 2026-09-27] resolveFolderSetJobId → resolveAnalysisJobId (용어집: 폴더 세트의 키는 analysis_job_id)
    private fun resolveAnalysisJobId(galleryId: Long, requestedAnalysisJobId: Long?, required: Boolean): Long? {
        if (requestedAnalysisJobId != null) {
            if (!aiFolderSetReader.setExists(galleryId, requestedAnalysisJobId)) {
                throw RecommendationException(RecommendationErrorCode.FOLDER_SET_NOT_FOUND)
            }
            return requestedAnalysisJobId
        }

        val latest = aiFolderSetReader.latestAnalysisJobId(galleryId)
        if (latest == null && required) throw RecommendationException(RecommendationErrorCode.FOLDER_SET_NOT_READY)
        return latest
    }

    /**
     * 사진마다 가장 최근 추천. [folderId]가 있으면 **지금 그 폴더에 든** 사진의 추천만 — 폴더 화면이 배지를
     * 그리는 경로다. 응답의 folderId도 현재 배정이다(추천 당시 폴더가 아니다). 거절한 추천은 그리지 않는다.
     *
     * 보는 것이라 작가도 볼 수 있다(`requireViewer`) — 부부가 무엇을 제안받았는지는 작가 화면에도
     * 쓸모가 있다. 셀렉 행이 없거나 추천이 한 번도 없었으면 빈 응답이지 404가 아니다 — 프론트가
     * 잡을 걸기 전 폴링부터 시작해도 오류로 보이지 않게 한다.
     */
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long, folderId: Long?): AiRecommendationListResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val selection = photoSelectionRepository.findByGalleryId(galleryId)
            ?: return AiRecommendationListResponse.empty(properties.viewUrlTtl.seconds)
        val selectionId = selection.requiredId
        val studioViewer = galleryAccessPolicy.isStudioManager(galleryId, userId)

        val job = aiSelectionJobRepository.findFirstBySelectionIdOrderByIdDesc(selectionId)
            ?.let { AiSelectionJobResponse.from(it) }
            ?.let { if (studioViewer) it.copy(prompt = null) else it }
        val latestRound = aiRecommendationRepository.findFirstBySelectionIdOrderByRoundDesc(selectionId)?.round
            ?: return AiRecommendationListResponse(
                round = null,
                job = job,
                photos = emptyList(),
                viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
            )

        // 사진마다 가장 최근 라운드의 행 하나. 거절 행은 표시하지 않는다(다음 계산이 뺄 근거로만 남는다).
        val latestByPhoto = aiRecommendationRepository.findAllBySelectionId(selectionId)
            .groupBy { it.photoId }
            .mapNotNull { (_, rows) -> rows.maxBy { it.round } }
            .filter { it.rejectedAt == null }
        val currentFolderByPhoto = aiFolderSetReader.detailIdsByPhotoId(latestByPhoto.map { it.photoId })
        val visible = when (folderId) {
            null -> latestByPhoto
            else -> latestByPhoto.filter { currentFolderByPhoto[it.photoId] == folderId }
        }
        val ordered = visible.sortedWith(
            compareBy<AiRecommendation, Long?>(nullsLast()) { currentFolderByPhoto[it.photoId] }.thenBy { it.rank },
        )

        return AiRecommendationListResponse(
            round = latestRound,
            job = job,
            photos = recommendationResponses(galleryId, selectionId, ordered, currentFolderByPhoto).map {
                if (studioViewer) it.copy(photo = it.photo.copy(score = null),
                    selected = it.selected && selection.status == com.soma.wes.selection.domain.PhotoSelectionStatus.SUBMITTED)
                else it
            },
            viewUrlTtlSeconds = properties.viewUrlTtl.seconds,
        )
    }

    /**
     * 추천 순서를 그대로 지키며 사진·담김 여부를 붙인다. 사진은 갤러리 스코프로 읽어, 휴지통에
     * 들어갔거나 이 갤러리의 것이 아닌 행은 조용히 빠진다.
     */
    private fun recommendationResponses(
        galleryId: Long,
        selectionId: Long,
        recommendations: List<AiRecommendation>,
        currentFolderByPhoto: Map<Long, Long>,
    ): List<AiRecommendationResponse> {
        val photoIds = recommendations.map { it.photoId }
        val photos = photoRepository.findAllByGalleryIdAndIdIn(galleryId, photoIds)
        val photoResponseById = photoViewAssembler.toResponses(photos).associateBy { it.photoId }
        val selectedPhotoIds = photoSelectionItemRepository.findAllBySelectionId(selectionId)
            .map { it.photoId }
            .toSet()

        return recommendations.mapNotNull { recommendation ->
            photoResponseById[recommendation.photoId]?.let { photo ->
                AiRecommendationResponse.of(
                    recommendation = recommendation,
                    folderId = currentFolderByPhoto[recommendation.photoId],
                    photo = photo,
                    selected = recommendation.photoId in selectedPhotoIds,
                )
            }
        }
    }
}
