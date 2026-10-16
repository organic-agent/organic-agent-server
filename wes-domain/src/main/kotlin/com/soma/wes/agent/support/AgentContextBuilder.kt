package com.soma.wes.agent.support

import com.soma.wes.agent.dto.AgentContextDto
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.dto.request.AgentMessageRequest
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/** 판단기에게 줄 갤러리 사정을 만든다 — 폴더별 장수, 촬영 순서와 시간대, 고른 장수, 지금 화면. */
@Component
class AgentContextBuilder {

    companion object {

        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun build(snapshot: AgentGallerySnapshotDto, screen: AgentMessageRequest.Screen?): AgentContextDto {
        val photosByConceptId = snapshot.photos.groupBy { it.conceptFolderId }
        val countByDetailId = snapshot.photos.groupingBy { it.detailFolderId }.eachCount()

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
                    AgentContextDto.Detail(id = detail.id, name = detail.name, photoCount = countByDetailId[detail.id] ?: 0)
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
        )
    }
}
