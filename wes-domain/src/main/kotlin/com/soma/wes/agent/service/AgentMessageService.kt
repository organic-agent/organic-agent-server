package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.AgentPendingOp
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
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

        /** 되묻기 선택지 수. 컨셉은 보통 4~6개라 다 보이되, 긴 목록이 답 한 줄(600자, history 상한)을 넘지 않게. */
        const val MAX_CLARIFY_CHOICES = 6
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
            is AgentDecisionDto.Clarify -> clarify(snapshot, decision, request.screen)
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
                pendingOp = turn.pendingOp,
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

    /** 못 거르는 조건만 있으면 사진을 보여 주지 않는다 — 갤러리 전체를 "결과"로 보이면 걸러진 줄 안다(#412 결정 3). */
    private fun filterPhotos(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.FilterPhotos): AgentMessageResponse {
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
        if (isUnsupportedOnly(decision.filter, decision.unsupported)) {
            return AgentMessageResponse(
                kind = AgentReplyKind.FILTER_PHOTOS,
                reply = agentReplyWriter.unsupportedOnly(decision.unsupported),
                unsupported = decision.unsupported,
            )
        }
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

    /** 못 거르는 조건·고른 사진 범위만으로는 고른 사진 전체가 된다 — 걸러진 것처럼 세거나 바꾸지 않는다. */
    private fun isUnsupportedOnly(filter: PhotoFilter, unsupported: List<UnsupportedCondition>): Boolean =
        unsupported.isNotEmpty() && filter.copy(selected = null) == PhotoFilter()

    /**
     * 신랑·신부 비율은 고른 사진이 있으면 고른 사진, 0장이면 갤러리 전체로 센다 — 셀렉 중에 묻는 비율은 "고른 것의 균형"이고,
     * 0장일 때 "고른 사진이 없어요"는 묻는 말에 답이 아니다(#412 결정 4). 답 첫머리에 기준을 밝힌다.
     */
    private fun stats(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.Stats): AgentMessageResponse {
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
        if (isUnsupportedOnly(decision.filter, decision.unsupported)) {
            return AgentMessageResponse(
                kind = AgentReplyKind.STATS,
                reply = agentReplyWriter.unsupportedOnly(decision.unsupported),
                unsupported = decision.unsupported,
            )
        }
        val noneSelected = decision.metric == StatsMetric.BY_SUBJECTS && decision.scope == StatsScope.SELECTED && snapshot.photos.none { it.selected }
        val counted = if (noneSelected) decision.copy(scope = StatsScope.GALLERY) else decision
        val stats = agentStatsCounter.count(snapshot, counted)

        return AgentMessageResponse(
            kind = AgentReplyKind.STATS,
            reply = agentReplyWriter.stats(
                stats,
                decision.unsupported,
                filtered = decision.filter.copy(selected = null) != PhotoFilter(),
                noneSelected = noneSelected,
            ),
            stats = stats,
            unsupported = decision.unsupported,
        )
    }

    /**
     * 제출된 선택은 바꿀 수 없어 계획을 만들지 않는다. 이동할 폴더가 이 갤러리에 없으면(판단기가 지어낸 id) 되묻는다.
     * 못 거르는 조건만 있으면("고른 것 중 눈 감은 거 빼줘") 조건이 없는 셈이라 고른 사진 전부가 바뀐다 — 계획 없이 되묻는다.
     */
    private fun planAction(snapshot: AgentGallerySnapshotDto, decision: AgentDecisionDto.PlanAction): AgentMessageResponse {
        if (!snapshot.selectionEditable) return clarify(agentReplyWriter.notEditable())
        if (isPartnerMissing(snapshot, decision.filter)) return clarify(agentReplyWriter.noPartner())
        if (isUnsupportedOnly(decision.filter, decision.unsupported)) return clarify(agentReplyWriter.unsupportedOnlyPlan(decision.unsupported))
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

    /**
     * 판단기의 되묻기. 하려던 일([AgentDecisionDto.Clarify.pending])이 있으면 지금 상태로 선택지를 붙이고 그 일을 응답에 실어 다음 질문으로 잇게 한다
     * (#412 결정 9). 선택지 문장과 숫자는 서버가 만든다 — 판단기 문장은 넣기·빼기에서 버리고, 옮기기·이름 바꾸기에서는 폴더 번호를 덧붙인다.
     * 하려던 일이 없거나 이미 제출해 할 수 없으면 판단기 문장 그대로다.
     */
    private fun clarify(
        snapshot: AgentGallerySnapshotDto,
        decision: AgentDecisionDto.Clarify,
        screen: AgentMessageRequest.Screen?,
    ): AgentMessageResponse {
        val pending = decision.pending ?: return clarify(decision.question)
        if (pending != AgentPendingOp.RENAME && !snapshot.selectionEditable) return clarify(agentReplyWriter.notEditable())

        return when (pending) {
            AgentPendingOp.SELECT_REMOVE -> removeChoices(snapshot)
            AgentPendingOp.SELECT_ADD -> addChoices(snapshot, screen)
            AgentPendingOp.MOVE, AgentPendingOp.RENAME -> {
                val candidates = decision.folders.mapNotNull { candidate(snapshot, it) }
                pendingClarify(agentReplyWriter.withFolderChoices(decision.question, candidates), pending, candidates)
            }
        }
    }

    /** 고른 사진이 있는 컨셉만, 화면의 폴더 순서로. 미분류에만 고른 사진은 선택지가 되지 않는다 — 장수 문장은 전체 고른 수로 말한다. */
    private fun removeChoices(snapshot: AgentGallerySnapshotDto): AgentMessageResponse {
        val selected = snapshot.photos.filter { it.selected }
        if (selected.isEmpty()) return clarify(agentReplyWriter.nothingToRemove())

        val countByConceptId = selected.groupingBy { it.conceptFolderId }.eachCount()
        val concepts = snapshot.concepts.filter { (countByConceptId[it.id] ?: 0) > 0 }.take(MAX_CLARIFY_CHOICES)
        val rows = concepts.map { AgentMessageResponse.Stats.Row(label = it.name, count = countByConceptId.getValue(it.id)) }
        val candidates = concepts.map { AgentMessageResponse.FolderCandidate(conceptFolderId = it.id, name = it.name) }
        return pendingClarify(agentReplyWriter.removeChoices(selected.size, rows), AgentPendingOp.SELECT_REMOVE, candidates)
    }

    /** 화면에 폴더가 열려 있으면 그 폴더 전체인지 한 장인지(#412 결정 5), 아니면 컨셉을 선택지로. */
    private fun addChoices(snapshot: AgentGallerySnapshotDto, screen: AgentMessageRequest.Screen?): AgentMessageResponse {
        val conceptId = screen?.conceptFolderId
        val open = conceptId?.let { candidate(snapshot, AgentDecisionDto.FolderTarget(conceptFolderId = it, detailFolderId = screen.detailFolderId)) }
        if (open != null) {
            val count = snapshot.photos.count { photo ->
                photo.conceptFolderId == open.conceptFolderId && (open.detailFolderId == null || photo.detailFolderId == open.detailFolderId)
            }
            return pendingClarify(agentReplyWriter.addFromScreen(open.name, count), AgentPendingOp.SELECT_ADD, listOf(open))
        }

        val concepts = snapshot.concepts.take(MAX_CLARIFY_CHOICES)
        val candidates = concepts.map { AgentMessageResponse.FolderCandidate(conceptFolderId = it.id, name = it.name) }
        return pendingClarify(agentReplyWriter.addChoices(concepts.map { it.name }), AgentPendingOp.SELECT_ADD, candidates)
    }

    private fun pendingClarify(
        reply: String,
        pending: AgentPendingOp,
        candidates: List<AgentMessageResponse.FolderCandidate>,
    ) = AgentMessageResponse(
        kind = AgentReplyKind.CLARIFY,
        reply = reply,
        folderCandidates = candidates.takeIf { it.isNotEmpty() },
        pendingOp = pending,
    )

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
