package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.dto.AgentCheckItemDto
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.PhotoReactionsDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.AgentTurnDto
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.service.port.DecisionClient
import com.soma.wes.agent.support.AgentActionPlanner
import com.soma.wes.agent.support.AgentContextBuilder
import com.soma.wes.agent.support.AgentExplainWriter
import com.soma.wes.agent.support.AgentGalleryLoader
import com.soma.wes.agent.support.AgentPhotoFinder
import com.soma.wes.agent.support.AgentQuestionLimiter
import com.soma.wes.agent.repository.AgentReactionQueryRepository
import com.soma.wes.agent.support.AgentReplyWriter
import com.soma.wes.agent.support.AgentStatsCounter
import com.soma.wes.agent.support.AgentSubmitChecker
import com.soma.wes.folder.domain.FolderName
import com.soma.wes.gallery.support.GalleryAccessPolicy
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 셀렉 도우미의 질문 창구. 질문 하나 → 판단 한 번 → 답 하나다. 대화는 저장하지 않는다 — 웹이 최근 대화를 돌려보내면 판단 문맥으로만 쓴다.
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
    private val agentExplainWriter: AgentExplainWriter,
    private val agentSubmitChecker: AgentSubmitChecker,
    private val agentReactionQueryRepository: AgentReactionQueryRepository,
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

        /** 반응 본문을 싣는 사진 수 상한 — "가족이 좋아한 사진"은 수십 장이지만 "메모 없는 사진"은 수천 장일 수 있다. */
        const val MAX_REACTION_PHOTOS = 200
        private const val UNAVAILABLE = "Unavailable"
    }

    /** 부부만 묻는다. 작가 화면에는 도우미가 없다 — 고르는 사람의 도구다. 사용자별 질문 상한을 넘으면 429다([AgentQuestionLimiter]). */
    fun send(galleryId: Long, userId: Long, request: AgentMessageRequest): AgentMessageResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        val question = request.text.trim()
        if (question.isEmpty()) throw AgentException(AgentErrorCode.EMPTY_QUESTION)
        val history = loadHistory(request.history)
        agentQuestionLimiter.acquire(userId)

        val snapshot = agentGalleryLoader.load(galleryId, userId)
        val context = agentContextBuilder.build(snapshot, request.screen, history)

        val decision = decide(question, context)
        log.info("셀렉 도우미 판단: galleryId={}, decision={}", galleryId, decision::class.simpleName)

        return when (decision) {
            is AgentDecisionDto.FindFolder -> findFolder(snapshot, decision)
            is AgentDecisionDto.FilterPhotos -> filterPhotos(snapshot, decision)
            is AgentDecisionDto.Stats -> stats(snapshot, decision)
            is AgentDecisionDto.PlanAction -> planAction(snapshot, decision)
            is AgentDecisionDto.PlanRename -> planRename(snapshot, decision)
            is AgentDecisionDto.Clarify -> clarify(decision.question)
            is AgentDecisionDto.Explain -> explain(snapshot, decision)
            is AgentDecisionDto.Check -> check(snapshot, decision)
        }
    }

    /**
     * 웹이 돌려보낸 최근 대화를 판단 문맥으로 옮긴다. 모양(개수·USER로 시작해 번갈아·ASSISTANT로 끝남·길이)을 어기면 400이다 —
     * Converse는 user·assistant가 번갈아 와야 받는다. 내용은 믿지 않는다: 폴더 id는 열 때 [candidate]가, 사진은 [AgentPhotoFinder]가 다시 본다.
     */
    private fun loadHistory(turns: List<AgentMessageRequest.Turn>): List<AgentTurnDto> {
        if (turns.size > AgentMessageRequest.MAX_HISTORY || turns.size % 2 != 0) throw AgentException(AgentErrorCode.INVALID_HISTORY)

        return turns.mapIndexed { index, turn ->
            val expectedRole = if (index % 2 == 0) AgentTurnRole.USER else AgentTurnRole.ASSISTANT
            val maxLength = when (turn.role) {
                AgentTurnRole.USER -> AgentMessageRequest.MAX_TEXT_LENGTH
                AgentTurnRole.ASSISTANT -> AgentMessageRequest.MAX_ASSISTANT_TEXT_LENGTH
            }
            val text = turn.text.trim()
            if (turn.role != expectedRole || text.isEmpty() || text.length > maxLength) throw AgentException(AgentErrorCode.INVALID_HISTORY)

            AgentTurnDto(
                role = turn.role,
                text = text,
                kind = turn.kind,
                folderCandidates = turn.folderCandidates.map {
                    AgentDecisionDto.FolderTarget(conceptFolderId = it.conceptFolderId, detailFolderId = it.detailFolderId)
                },
                filter = turn.filter,
                checkCodes = turn.checkCodes,
            )
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
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
        val photos = agentPhotoFinder.find(snapshot, decision.filter)
        val reactions = reactions(decision.filter, photos.map { it.photoId })

        return AgentMessageResponse(
            kind = AgentReplyKind.FILTER_PHOTOS,
            reply = agentReplyWriter.filtered(photos.size, decision.unsupported, withReactions = reactions.isNotEmpty()),
            view = view(decision.filter, photos.map { it.photoId }, reactions),
            unsupported = decision.unsupported,
        )
    }

    /** 배우자가 아직 갤러리에 없는데 배우자를 가리키면, 0장이라고 답하지 않고 그 까닭을 말한다(#403 5-3). */
    private fun isPartnerMissing(snapshot: AgentGallerySnapshotDto, filter: PhotoFilter): Boolean =
        filter.refersToPartner() && snapshot.partnerUserIds.isEmpty()

    private fun stats(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Stats): AgentMessageResponse {
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
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
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
        val targetId = decision.targetDetailFolderId.takeIf { decision.op == AgentActionOp.MOVE }
        val targetName = if (targetId == null) null else detailName(snapshot, targetId) ?: return clarify(MOVE_TARGET_UNKNOWN)

        val matched = agentPhotoFinder.find(snapshot, decision.filter)
        val action = agentActionPlanner.plan(snapshot, decision, matched)

        return AgentMessageResponse(
            kind = AgentReplyKind.PLAN_ACTION,
            reply = agentReplyWriter.plan(decision.op, action, targetName, decision.unsupported),
            view = view(decision.filter, matched.map { it.photoId }, reactions(decision.filter, matched.map { it.photoId })),
            action = action,
            unsupported = decision.unsupported,
        )
    }

    /**
     * 이름 바꾸기 계획 — 계획 순간의 이름을 [AgentActionStep.expectedName]에 담아, 확인을 누르기 전에 배우자가 바꿨으면 실행이 건너뛴다.
     * 초대받은 부부는 제출 뒤 폴더를 못 바꾼다(`requireFolderEditor`) — 개인 갤러리 부부는 제출 뒤에도 바꾼다.
     */
    private fun planRename(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.PlanRename): AgentMessageResponse {
        if (!snapshot.selectionEditable && !snapshot.personal) return clarify(agentReplyWriter.folderNotEditable())
        if (decision.name.length > FolderName.MAX_LENGTH) return clarify(agentReplyWriter.nameTooLong())
        val concept = snapshot.concepts.firstOrNull { it.id == decision.target.conceptFolderId } ?: return clarify(agentReplyWriter.noFolder())
        val detailId = decision.target.detailFolderId
        val currentName = if (detailId == null) {
            concept.name
        } else {
            concept.details.firstOrNull { it.id == detailId }?.name ?: return clarify(agentReplyWriter.noFolder())
        }
        if (currentName == decision.name) return clarify(agentReplyWriter.sameName(currentName))

        val step = AgentActionStep(
            op = if (detailId == null) AgentActionOp.RENAME_CONCEPT else AgentActionOp.RENAME_DETAIL,
            conceptFolderId = concept.id,
            detailFolderId = detailId,
            name = decision.name,
            expectedName = currentName,
        )
        return AgentMessageResponse(
            kind = AgentReplyKind.PLAN_ACTION,
            reply = agentReplyWriter.rename(currentName, decision.name),
            view = AgentMessageResponse.View(conceptFolderId = concept.id, detailFolderId = detailId),
            action = AgentMessageResponse.Action(steps = listOf(step), photoCount = 0, skippedPhotoCount = 0, overLimitBy = 0),
        )
    }

    private fun detailName(snapshot: AgentGallerySnapshotDto, detailId: Long): String? =
        snapshot.concepts.firstNotNullOfOrNull { concept ->
            concept.details.firstOrNull { it.id == detailId }?.let { detail -> "${concept.name} > ${detail.name}" }
        }

    /** 한 폴더만 거른 조건이면 그 폴더를 연 화면으로 보여 준다. 반응으로 걸렀으면 반응 본문도 싣는다. */
    private fun view(filter: PhotoFilter, photoIds: List<Long>, reactions: List<PhotoReactionsDto> = emptyList()): AgentMessageResponse.View =
        AgentMessageResponse.View(
            conceptFolderId = filter.conceptFolderIds.singleOrNull()?.takeIf { filter.detailFolderIds.isEmpty() },
            detailFolderId = filter.detailFolderIds.singleOrNull()?.takeIf { filter.conceptFolderIds.isEmpty() },
            filter = filter,
            photoIds = photoIds,
            reactions = reactions.takeIf { it.isNotEmpty() }?.map { it.toResponse() },
        )

    /** 반응으로 거른 결과에만, 앞에서부터 [MAX_REACTION_PHOTOS]장까지. 그 밖의 조건은 사진 목록만 — 본문은 화면이 사진 상세에서 보여 준다. */
    private fun reactions(filter: PhotoFilter, photoIds: List<Long>): List<PhotoReactionsDto> =
        if (filter.hasReactionCondition()) agentReactionQueryRepository.findByPhotoIds(photoIds.take(MAX_REACTION_PHOTOS)) else emptyList()

    private fun PhotoReactionsDto.toResponse() = AgentMessageResponse.PhotoReactions(
        photoId = photoId,
        rating = rating,
        memo = memo,
        comments = comments.map { AgentMessageResponse.PhotoReactions.Comment(author = it.author, content = it.content) },
        likedBy = likedBy,
    )

    private fun clarify(question: String) = AgentMessageResponse(kind = AgentReplyKind.CLARIFY, reply = question)

    private fun explain(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Explain): AgentMessageResponse {
        val (reply, links) = agentExplainWriter.write(snapshot, decision.topic)
        return AgentMessageResponse(
            kind = AgentReplyKind.EXPLAIN,
            reply = reply,
            explain = AgentMessageResponse.Explain(topic = decision.topic, links = links),
        )
    }

    /**
     * 제출 전 점검 — 서버가 센 숫자와 정해 둔 문장(C-1). [AgentDecisionDto.Check.focus]가 있으면 그 항목 하나의 사진·컨셉을 연다.
     * 그 항목이 지금은 걸리지 않으면(그사이 고쳤으면) 그렇다고 말한다 — 앞 답의 사진 id를 다시 쓰지 않고 지금 상태로 다시 센다.
     */
    private fun check(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Check): AgentMessageResponse {
        val items = agentSubmitChecker.check(snapshot)
        val selectedCount = snapshot.photos.count { it.selected }
        val focus = decision.focus
        if (focus == null) {
            return AgentMessageResponse(
                kind = AgentReplyKind.CHECK,
                reply = agentReplyWriter.check(selectedCount, items),
                check = checkResponse(snapshot, selectedCount, items),
            )
        }

        val item = items.firstOrNull { it.code == focus } ?: return clarify(agentReplyWriter.checkNotFound())
        return AgentMessageResponse(
            kind = AgentReplyKind.CHECK,
            reply = agentReplyWriter.checkLine(item),
            view = AgentMessageResponse.View(conceptFolderId = item.conceptFolderIds.singleOrNull(), photoIds = item.photoIds.takeIf { it.isNotEmpty() }),
            check = checkResponse(snapshot, selectedCount, listOf(item)),
        )
    }

    private fun checkResponse(snapshot: AgentGallerySnapshotDto, selectedCount: Int, items: List<AgentCheckItemDto>) =
        AgentMessageResponse.Check(
            selectedCount = selectedCount,
            limit = snapshot.maxSelectablePhotoCount,
            items = items.map { item ->
                AgentMessageResponse.CheckItem(
                    code = item.code,
                    severity = item.code.severity,
                    count = item.count,
                    photoIds = item.photoIds,
                    conceptFolderIds = item.conceptFolderIds,
                )
            },
        )
}
