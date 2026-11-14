package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentScreen
import com.soma.wes.agent.domain.ExplainTopic
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.retouch.domain.RetouchRoundStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 사용법·상태 답을 주제별 템플릿으로 쓴다(#348 ⑤, B-1). 문장의 사실은 전부 코드·문서에서 확인한 제품 규칙이고 숫자는 스냅숏에서 온다 —
 * LLM이 쓰지 않으니 지어내는 답이 구조적으로 0이다(QA-8). 주제 밖은 "모른다"로 끝내고 작가에게 돌린다.
 *
 * 규칙 출처: 제출·되돌리기 `PhotoSelectionService`(계약 장수 딱 맞춰야 제출, 되돌리기는 작가만, 개인 갤러리는 내보내기·취소) ·
 * 보정 `RetouchRound`(회차 단위, 제출된 회차 수 = 쓴 횟수) · 공유 `CollabSession`(링크 7일) · 별점·메모·댓글 컨트롤러 문서 · 휴지통은 작가만.
 */
@Component
class AgentExplainWriter(
    private val clock: Clock,
) {

    companion object {

        private val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val DEADLINE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    }

    fun write(snapshot: AgentGallerySnapshotDto, topic: ExplainTopic): Pair<String, List<AgentMessageResponse.Explain.Link>> = when (topic) {
        ExplainTopic.SUBMIT -> submit(snapshot)
        ExplainTopic.WITHDRAW -> withdraw(snapshot)
        ExplainTopic.DEADLINE -> deadline(snapshot)
        ExplainTopic.MAX_PHOTOS -> maxPhotos(snapshot)
        ExplainTopic.RETOUCH_ROUNDS -> retouchRounds(snapshot)
        ExplainTopic.SHARE_FAMILY -> "공유폴더를 만들어 링크를 보내면 가족이 로그인 없이 사진을 보고 좋아요·댓글을 남길 수 있어요. 링크는 7일 동안 유효해요. " +
            "가족 반응은 '가족이 좋아한 사진만'처럼 저한테 물어 모아 볼 수 있어요." to listOf(link("공유폴더 만들기", AgentScreen.COLLAB))
        ExplainTopic.RATING -> "사진마다 별 1~5개를 매길 수 있어요. 별점은 사진당 하나라 두 분이 같은 칸을 함께 써요. " +
            "'별 4개 이상만 보여줘'처럼 저한테 물으면 모아 볼 수 있어요." to emptyList()
        ExplainTopic.MEMO -> memo(snapshot)
        ExplainTopic.COMMENT -> "사진 상세에서 댓글을 남길 수 있어요(500자까지, 선택 마감 전). 제출한 뒤에도 쓸 수 있고, 수정은 안 되며 본인 댓글만 지울 수 있어요. " +
            "'댓글 달린 사진만'으로 모아 볼 수 있어요." to emptyList()
        ExplainTopic.DELETE_PHOTO -> "사진을 지우는 건 담당 작가님만 할 수 있어요. 빼고 싶은 사진은 선택에서 빼는 것으로 충분하고, 그건 제가 도와드릴 수 있어요." to emptyList()
        ExplainTopic.DOWNLOAD -> "원본 파일은 이 화면에서 내려받을 수 없어요. 보정 결과는 보정 화면에서 확인하고, 최종 전달은 작가님이 해요." to listOf(link("보정 화면으로", AgentScreen.RETOUCH))
        ExplainTopic.AI_RECOMMEND -> "AI 추천은 폴더마다 계약 장수에 비례해 점수 순으로 골라 배지로 표시해요. 비슷한 연사에서는 대표 한 장만 추천해요. " +
            "셀렉 화면의 AI 추천 버튼으로 요청하고, 추천은 참고일 뿐 선택은 두 분이 정해요." to listOf(link("AI 추천 요청", AgentScreen.RECOMMEND))
        ExplainTopic.UNKNOWN -> "그건 제가 아직 모르는 내용이에요. 작가님께 여쭤봐 주세요." to emptyList()
    }

    private fun submit(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> {
        if (snapshot.personal) {
            return "개인 갤러리는 제출 대신 '요청서 내보내기'로 끝내요. 내보낸 뒤 보정본이 올라오기 전까지는 취소하고 다시 고를 수 있어요." to
                listOf(link("셀렉 화면으로", AgentScreen.SELECTION))
        }
        if (!snapshot.selectionEditable) return "이미 제출하셨어요. 제출 뒤에는 담기·빼기가 막히고, 되돌리는 건 담당 작가님만 할 수 있어요." to emptyList()
        val selected = snapshot.photos.count { it.selected }
        val limit = snapshot.maxSelectablePhotoCount
        val readiness = when {
            limit == null -> "계약 장수가 정해져 있지 않아요."
            selected == limit -> "지금 ${selected}장으로 계약 장수를 딱 맞춰 제출할 수 있어요."
            selected < limit -> "계약 ${limit}장을 딱 맞춰야 제출돼요. 지금 ${selected}장이라 ${limit - selected}장 더 골라야 해요."
            else -> "계약 ${limit}장을 딱 맞춰야 제출돼요. 지금 ${selected}장이라 ${selected - limit}장 빼야 해요."
        }
        return "고르기를 마치면 셀렉 화면의 제출 버튼으로 작가님께 넘겨요. $readiness 제출 뒤에는 담기·빼기가 막혀요." to
            listOf(link("셀렉 화면으로", AgentScreen.SELECTION))
    }

    private fun withdraw(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> {
        if (snapshot.personal) {
            return "내보낸 요청서는 보정본이 올라오기 전까지 두 분 중 누구나 취소하고 다시 고를 수 있어요." to listOf(link("셀렉 화면으로", AgentScreen.SELECTION))
        }
        if (snapshot.selectionEditable) return "아직 제출하지 않아서 되돌릴 것이 없어요. 지금은 자유롭게 담고 뺄 수 있어요." to emptyList()
        return "제출을 되돌리는 건 담당 작가님만 할 수 있어요. 작가님께 요청해 주세요. 보정 결과가 올라온 뒤에는 되돌릴 수 없어요." to emptyList()
    }

    private fun deadline(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> {
        val deadline = snapshot.selectionDeadline ?: return "정해진 선택 마감이 없어요. 작가님이 정하면 여기서 알려 드릴게요." to emptyList()
        val now = ZonedDateTime.now(clock)
        val at = deadline.withZoneSameInstant(ZONE).format(DEADLINE_FORMAT)
        return if (deadline.isBefore(now)) {
            "선택 마감($at)이 지나 선택을 바꿀 수 없어요. 더 고르려면 작가님께 연장을 요청해 주세요." to emptyList()
        } else {
            "선택 마감은 ${at}이에요. 그 전에 제출해 주세요." to emptyList()
        }
    }

    private fun maxPhotos(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> {
        val selected = snapshot.photos.count { it.selected }
        val limit = snapshot.maxSelectablePhotoCount
            ?: return "계약 장수가 정해져 있지 않아요. 지금 ${selected}장 골랐어요." to emptyList()
        val remaining = when {
            selected < limit -> "${limit - selected}장 더 고를 수 있어요."
            selected == limit -> "꽉 채웠어요."
            else -> "${selected - limit}장 넘어서 그만큼 빼야 제출돼요."
        }
        return "계약 장수는 ${limit}장이고 지금 ${selected}장 골라 $remaining 장수를 늘리고 싶으면 '계약 장수 상향 요청'으로 작가님께 요청할 수 있어요." to emptyList()
    }

    private fun retouchRounds(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> {
        val max = snapshot.maxRetouchRoundCount
        val used = snapshot.retouchRoundsUsed
        val count = if (max == null) "계약 보정 횟수는 정해져 있지 않고, 지금까지 ${used}회 요청했어요." else "계약 보정 ${max}회 중 ${used}회를 썼어요."
        val current = when (snapshot.latestRetouchRoundStatus) {
            RetouchRoundStatus.DRAFTING -> " 지금 회차는 작성 중이에요."
            RetouchRoundStatus.REQUESTED -> " 지금 회차는 제출돼 작가님이 작업 중이라 요청을 고칠 수 없어요."
            RetouchRoundStatus.COMPLETED -> " 지난 회차는 끝났고 다음 회차를 시작할 수 있어요."
            null -> ""
        }
        return "$count$current 보정은 셀렉을 제출한 뒤 보정 화면에서 사진마다 요청문과 포인트를 적어 회차 단위로 제출해요." to
            listOf(link("보정 화면으로", AgentScreen.RETOUCH))
    }

    private fun memo(snapshot: AgentGallerySnapshotDto): Pair<String, List<AgentMessageResponse.Explain.Link>> =
        if (snapshot.personal) {
            "사진마다 메모 한 칸을 적을 수 있어요. 두 분이 같은 칸을 함께 쓰고 다시 적으면 덮어써요. '메모 남긴 사진만'으로 모아 볼 수 있어요." to emptyList()
        } else {
            "초대받은 갤러리에서는 메모 대신 사진 댓글을 써요. 작가님과 두 분이 같이 보는 글이에요." to emptyList()
        }

    private fun link(label: String, to: AgentScreen) = AgentMessageResponse.Explain.Link(label = label, to = to)
}
