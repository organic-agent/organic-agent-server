package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.agent.support.AgentActionPlanner
import com.soma.wes.agent.support.AgentContextBuilder
import com.soma.wes.agent.support.AgentGalleryLoader
import com.soma.wes.agent.support.AgentPhotoFinder
import com.soma.wes.agent.support.AgentQuestionLimiter
import com.soma.wes.agent.support.AgentReplyWriter
import com.soma.wes.agent.support.AgentStatsCounter
import com.soma.wes.gallery.support.GalleryAccessPolicy
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 셀렉 도우미의 질문 창구. 질문 하나 → 판단 한 번 → 답 하나다. 이전 대화는 기억하지 않는다.
 *
 * 아무것도 쓰지 않는다. 사진을 넣고 빼고 옮기는 일은 계획만 돌려주고, 사용자가 확인하면 [AgentActionService]가 한다.
 * 트랜잭션이 없다 — 갤러리는 [AgentGalleryLoader]가 읽고 닫은 뒤에 판단(Bedrock)을 기다린다.
 */
@Service
class AgentMessageService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val agentGalleryLoader: AgentGalleryLoader,
    private val agentContextBuilder: AgentContextBuilder,
    private val decisionClient: DecisionClient,
    private val agentPhotoFinder: AgentPhotoFinder,
    private val agentStatsCounter: AgentStatsCounter,
    private val agentActionPlanner: AgentActionPlanner,
    private val agentReplyWriter: AgentReplyWriter,
    private val agentQuestionLimiter: AgentQuestionLimiter,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {

        /** 후보가 많으면 고르기 어렵다. 2~3개만 보여 준다. */
        const val MAX_FOLDER_CANDIDATES = 3
        const val MOVE_TARGET_UNKNOWN = "어느 폴더로 옮길지 찾지 못했어요. 폴더 이름으로 다시 말해 주세요."

        /** 판단 한 번의 지연. `result` 태그는 고른 주문서 종류 또는 판단 실패(`Unavailable`)다 — 응답 시간 목표(p50 1.6초)를 운영에서 본다. */
        const val DECISION_TIMER = "agent.decision"
        private const val RESULT_TAG = "result"
        private const val UNAVAILABLE = "Unavailable"
    }

    /** 부부만 묻는다. 작가 화면에는 도우미가 없다 — 고르는 사람의 도구다. 사용자별 질문 상한을 넘으면 429다([AgentQuestionLimiter]). */
    fun send(galleryId: Long, userId: Long, request: AgentMessageRequest): AgentMessageResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        val question = request.text.trim()
        if (question.isEmpty()) throw AgentException(AgentErrorCode.EMPTY_QUESTION)
        agentQuestionLimiter.acquire(userId)

        val snapshot = agentGalleryLoader.load(galleryId)
        val context = agentContextBuilder.build(snapshot, request.screen)

        val decision = decide(question, context)
        log.info("셀렉 도우미 판단: galleryId={}, decision={}", galleryId, decision::class.simpleName)

        return when (decision) {
            is AgentDecisionDto.FindFolder -> findFolder(snapshot, decision)
            is AgentDecisionDto.FilterPhotos -> filterPhotos(snapshot, decision)
            is AgentDecisionDto.Stats -> stats(snapshot, decision)
            is AgentDecisionDto.PlanAction -> planAction(snapshot, decision)
            is AgentDecisionDto.Clarify -> clarify(decision.question)
        }
    }

    private fun decide(question: String, context: AgentContextDto): AgentDecisionDto {
        val sample = Timer.start(meterRegistry)
        val decision = try {
            decisionClient.decide(question, context)
        } catch (e: AgentException) {
            sample.stop(meterRegistry.timer(DECISION_TIMER, RESULT_TAG, UNAVAILABLE))
            throw e
        }
        sample.stop(meterRegistry.timer(DECISION_TIMER, RESULT_TAG, decision::class.simpleName ?: UNAVAILABLE))
        return decision
    }

    private fun findFolder(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.FindFolder): AgentMessageResponse {
        val candidates = decision.targets.mapNotNull { target -> candidate(snapshot, target) }.take(MAX_FOLDER_CANDIDATES)
        val first = candidates.firstOrNull() ?: return clarify(agentReplyWriter.noFolder())

        return AgentMessageResponse(
            kind = AgentReplyKind.FIND_FOLDER,
            reply = agentReplyWriter.folder(candidates),
            view = AgentMessageResponse.View(conceptFolderId = first.conceptFolderId, detailFolderId = first.detailFolderId),
            folderCandidates = candidates.takeIf { it.size > 1 },
        )
    }

    /** 판단기가 고른 폴더가 이 갤러리에 있을 때만 후보가 된다. 세부 폴더는 컨셉 > 세부로 부른다. */
    private fun candidate(
        snapshot: AgentGallerySnapshotDto,
        target: AgentDecisionDto.FolderTarget,
    ): AgentMessageResponse.FolderCandidate? {
        val concept = snapshot.concepts.firstOrNull { it.id == target.conceptFolderId } ?: return null
        val detailId = target.detailFolderId ?: return AgentMessageResponse.FolderCandidate(conceptFolderId = concept.id, name = concept.name)
        val detail = concept.details.firstOrNull { it.id == detailId } ?: return null
        return AgentMessageResponse.FolderCandidate(
            conceptFolderId = concept.id,
            detailFolderId = detail.id,
            name = "${concept.name} > ${detail.name}",
        )
    }

    private fun filterPhotos(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.FilterPhotos): AgentMessageResponse {
        val photos = agentPhotoFinder.find(snapshot.photos, decision.filter)

        return AgentMessageResponse(
            kind = AgentReplyKind.FILTER_PHOTOS,
            reply = agentReplyWriter.filtered(photos.size, decision.unsupported),
            view = view(decision.filter, photos.map { it.photoId }),
            unsupported = decision.unsupported,
        )
    }

    private fun stats(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Stats): AgentMessageResponse {
        val stats = agentStatsCounter.count(snapshot, decision)

        return AgentMessageResponse(
            kind = AgentReplyKind.STATS,
            reply = agentReplyWriter.stats(stats, decision.unsupported),
            stats = stats,
            unsupported = decision.unsupported,
        )
    }

    /**
     * 제출된 선택은 바꿀 수 없어 계획을 만들지 않는다. 이동할 폴더가 이 갤러리에 없으면(판단기가 지어낸 id) 되묻는다.
     */
    private fun planAction(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.PlanAction): AgentMessageResponse {
        if (!snapshot.selectionEditable) return clarify(agentReplyWriter.notEditable())
        val targetId = decision.targetDetailFolderId.takeIf { decision.op == AgentActionOp.MOVE }
        val targetName = if (targetId == null) null else detailName(snapshot, targetId) ?: return clarify(MOVE_TARGET_UNKNOWN)

        val matched = agentPhotoFinder.find(snapshot.photos, decision.filter)
        val action = agentActionPlanner.plan(snapshot, decision, matched)

        return AgentMessageResponse(
            kind = AgentReplyKind.PLAN_ACTION,
            reply = agentReplyWriter.plan(decision.op, action, targetName, decision.unsupported),
            view = view(decision.filter, matched.map { it.photoId }),
            action = action,
            unsupported = decision.unsupported,
        )
    }

    private fun detailName(snapshot: AgentGallerySnapshotDto, detailId: Long): String? =
        snapshot.concepts.firstNotNullOfOrNull { concept ->
            concept.details.firstOrNull { it.id == detailId }?.let { detail -> "${concept.name} > ${detail.name}" }
        }

    /** 한 폴더만 거른 조건이면 그 폴더를 연 화면으로 보여 준다. */
    private fun view(filter: PhotoFilter, photoIds: List<Long>): AgentMessageResponse.View =
        AgentMessageResponse.View(
            conceptFolderId = filter.conceptFolderIds.singleOrNull()?.takeIf { filter.detailFolderIds.isEmpty() },
            detailFolderId = filter.detailFolderIds.singleOrNull()?.takeIf { filter.conceptFolderIds.isEmpty() },
            filter = filter,
            photoIds = photoIds,
        )

    private fun clarify(question: String) = AgentMessageResponse(kind = AgentReplyKind.CLARIFY, reply = question)
}
