package com.soma.wes.agent.support

import com.soma.wes.agent.domain.AgentCheckCode
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.dto.AgentGallerySnapshotDto
import com.soma.wes.agent.repository.projection.AgentPhotoRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentSubmitCheckerUnitTest {

    private val checker = AgentSubmitChecker()

    @Test
    fun `신부·신랑 단독이 합쳐 10장 이상이고 한쪽이 두 배 이상이면 치우침을 알린다`() {
        // given
        val snapshot = snapshot(bride = 7, groom = 3)

        // when
        val item = checker.check(snapshot).single { it.code == AgentCheckCode.SUBJECT_IMBALANCE }

        // then
        assertThat(listOf(item.brideCount, item.groomCount, item.count)).containsExactly(7, 3, 4)
    }

    @Test
    fun `두 배가 안 되거나 단독 사진이 10장보다 적으면 치우침을 말하지 않는다`() {
        // given
        val notDouble = snapshot(bride = 6, groom = 4)
        val tooFew = snapshot(bride = 6, groom = 3)

        // when
        val codes = listOf(notDouble, tooFew).flatMap { snapshot -> checker.check(snapshot).map { it.code } }

        // then
        assertThat(codes).doesNotContain(AgentCheckCode.SUBJECT_IMBALANCE)
    }

    private fun snapshot(bride: Int, groom: Int): AgentGallerySnapshotDto {
        val subjects = List(bride) { AgentSubject.BRIDE } + List(groom) { AgentSubject.GROOM }
        val photos = subjects.mapIndexed { index, subject ->
            AgentPhotoRow(
                photoId = index.toLong(),
                conceptFolderId = null,
                detailFolderId = null,
                subject = subject,
                orientation = null,
                burstId = index,
                burstRank = 0,
                takenAt = null,
                selected = true,
            )
        }
        return AgentGallerySnapshotDto(
            galleryId = 1,
            viewerUserId = 1,
            partnerUserIds = emptySet(),
            maxSelectablePhotoCount = null,
            selectionEditable = true,
            concepts = emptyList(),
            photos = photos,
        )
    }
}
