package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentCheckCode
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.dto.AgentCheckItemDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component

/**
 * 제출 전 점검을 센다(#403 5-4·7단계). 스냅숏 한 번으로 다섯 항목을 모두 세고 걸린 항목만 [AgentCheckCode] 순서로 돌려준다.
 * 읽기만 한다 — 고치는 것은 부부가 정한다. 숫자는 같은 DB 상태의 SQL과 같아야 한다(QA-10, 통합 테스트).
 */
@Component
class AgentSubmitChecker {

    companion object {

        /** 신부·신랑 단독 사진이 이만큼은 있어야 치우침을 말한다 — 몇 장으로는 우연이다. */
        const val IMBALANCE_MIN_SOLO_PHOTOS = 10

        /** 많은 쪽이 적은 쪽의 이 배수 이상이면 치우쳤다고 본다. */
        const val IMBALANCE_RATIO = 2

        /** 1점 = 빼고 싶은 사진(인터뷰 10-15: 눈 감은 사진에 1점을 준다). */
        const val LOW_RATING = 1
    }

    fun check(snapshot: AgentGallerySnapshotDto): List<AgentCheckItemDto> {
        val selected = snapshot.photos.filter { it.selected }
        if (selected.isEmpty()) return emptyList()

        return listOfNotNull(
            overLimit(snapshot, selected.size),
            emptyConcepts(snapshot),
            burstDuplicates(selected),
            subjectImbalance(selected),
            lowRated(selected),
        )
    }

    private fun overLimit(snapshot: AgentGallerySnapshotDto, selectedCount: Int): AgentCheckItemDto? {
        val limit = snapshot.maxSelectablePhotoCount ?: return null
        if (selectedCount <= limit) return null
        return AgentCheckItemDto(code = AgentCheckCode.OVER_LIMIT, count = selectedCount - limit, limit = limit)
    }

    /** 사진이 한 장도 없는 컨셉은 고를 것이 없어 빼고 센다. */
    private fun emptyConcepts(snapshot: AgentGallerySnapshotDto): AgentCheckItemDto? {
        val photosByConceptId = snapshot.photos.groupBy { it.conceptFolderId }
        val empty = snapshot.concepts.filter { concept ->
            val photos = photosByConceptId[concept.id].orEmpty()
            photos.isNotEmpty() && photos.none { it.selected }
        }
        if (empty.isEmpty()) return null
        return AgentCheckItemDto(
            code = AgentCheckCode.EMPTY_CONCEPT,
            count = empty.size,
            conceptFolderIds = empty.map { it.id },
            conceptNames = empty.map { it.name },
        )
    }

    private fun burstDuplicates(selected: List<AgentPhotoRow>): AgentCheckItemDto? {
        val repeated = selected.filter { it.burstId != null }.groupBy { it.burstId }.values.filter { it.size > 1 }
        if (repeated.isEmpty()) return null
        val photoIds = repeated.flatten().map { it.photoId }
        return AgentCheckItemDto(
            code = AgentCheckCode.BURST_DUPLICATE,
            count = photoIds.size,
            photoIds = photoIds,
            burstGroups = repeated.size,
        )
    }

    private fun subjectImbalance(selected: List<AgentPhotoRow>): AgentCheckItemDto? {
        val bride = selected.count { it.subject == AgentSubject.BRIDE }
        val groom = selected.count { it.subject == AgentSubject.GROOM }
        val more = maxOf(bride, groom)
        val fewer = minOf(bride, groom)
        if (bride + groom < IMBALANCE_MIN_SOLO_PHOTOS || more < fewer * IMBALANCE_RATIO) return null
        return AgentCheckItemDto(
            code = AgentCheckCode.SUBJECT_IMBALANCE,
            count = more - fewer,
            brideCount = bride,
            groomCount = groom,
        )
    }

    private fun lowRated(selected: List<AgentPhotoRow>): AgentCheckItemDto? {
        val photoIds = selected.filter { it.rating == LOW_RATING }.map { it.photoId }
        if (photoIds.isEmpty()) return null
        return AgentCheckItemDto(code = AgentCheckCode.LOW_RATED_SELECTED, count = photoIds.size, photoIds = photoIds)
    }
}
