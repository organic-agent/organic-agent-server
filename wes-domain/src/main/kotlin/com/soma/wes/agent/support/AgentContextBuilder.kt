package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.AgentTurnDto
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/** 판단기에게 줄 갤러리 사정을 만든다 — 폴더별 장수·주된 피사체, 촬영 순서와 시간대, 고른 장수, 지금 화면, 최근 대화. */
@Component
class AgentContextBuilder {

    companion object {

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * 세부 폴더의 주된 피사체로 부르는 몫. 세부 폴더의 컷 종류 컬럼(V24에서 지움)은 물질화 때 한 번 다수결로 채워 사진을 옮기면
         * 어긋났다 — 그래서 저장하지 않고 질문마다 사진 피사체로 센다. "신부 단독 폴더"라고 부를 만큼 대부분이어야 한다.
         */
        private const val MAIN_SUBJECT_SHARE = 0.6
    }

    fun build(
        snapshot: AgentGallerySnapshotDto,
        screen: AgentMessageRequest.Screen?,
        history: List<AgentTurnDto> = emptyList(),
    ): AgentContextDto {
        val photosByConceptId = snapshot.photos.groupBy { it.conceptFolderId }
        val photosByDetailId = snapshot.photos.groupBy { it.detailFolderId }

        val shotOrderByConceptId = snapshot.concepts
            .sortedWith(compareBy(nullsLast()) { concept -> photosByConceptId[concept.id]?.mapNotNull { it.takenAt }?.minOrNull() })
            .mapIndexed { index, concept -> concept.id to index + 1 }
            .toMap()

        val concepts = snapshot.concepts.map { concept ->
            val photos = photosByConceptId[concept.id].orEmpty()
            val takenAts = photos.mapNotNull { it.takenAt }
            AgentContextDto.Concept(
                id = concept.id,
                name = concept.name,
                shotOrder = shotOrderByConceptId.getValue(concept.id),
                timeRange = takenAts.minOrNull()?.let { first -> "${first.format(TIME_FORMAT)}~${takenAts.max().format(TIME_FORMAT)}" },
                photoCount = photos.size,
                details = concept.details.map { detail ->
                    val detailPhotos = photosByDetailId[detail.id].orEmpty()
                    AgentContextDto.Detail(
                        id = detail.id,
                        name = detail.name,
                        photoCount = detailPhotos.size,
                        mainSubject = mainSubject(detailPhotos),
                    )
                },
            )
        }

        return AgentContextDto(
            concepts = concepts,
            selectedCount = snapshot.photos.count { it.selected },
            maxSelectablePhotoCount = snapshot.maxSelectablePhotoCount,
            screen = screen?.let {
                AgentContextDto.Screen(conceptFolderId = it.conceptFolderId, detailFolderId = it.detailFolderId, filter = it.filter)
            },
            history = history,
        )
    }

    /** 피사체를 아는 사진 중 [MAIN_SUBJECT_SHARE] 이상이 같은 피사체면 그것. 분석이 덜 끝난 사진은 세지 않는다. */
    private fun mainSubject(photos: List<AgentPhotoRow>): AgentSubject? {
        val subjects = photos.mapNotNull { it.subject }
        val (subject, count) = subjects.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return subject.takeIf { count >= subjects.size * MAIN_SUBJECT_SHARE }
    }
}
