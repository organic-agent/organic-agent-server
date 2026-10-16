package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.PhotoFilter
import com.soma.wes.agent.domain.StatsMetric
import com.soma.wes.agent.domain.StatsScope
import com.soma.wes.agent.domain.UnsupportedCondition
import com.soma.wes.agent.dto.AgentDecisionDto
import com.soma.wes.agent.dto.request.AgentMessageRequest
import com.soma.wes.agent.dto.response.AgentMessageResponse
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.fixture.AgentFixture
import com.soma.wes.agent.fixture.AgentScene
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.support.FakeDecisionClient
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class AgentMessageServiceTest @Autowired constructor(
    private val agentMessageService: AgentMessageService,
    private val fakeDecisionClient: FakeDecisionClient,
    private val agentFixture: AgentFixture,
    private val selectionFixture: SelectionFixture,
) {

    @BeforeEach
    fun resetDecisionClient() {
        fakeDecisionClient.reset()
    }

    private fun send(scene: AgentScene, decision: AgentDecisionDto, text: String = "질문"): AgentMessageResponse {
        fakeDecisionClient.decision = decision
        return agentMessageService.send(scene.galleryId, scene.memberId, AgentMessageRequest(text = text))
    }

    @Nested
    @DisplayName("판단기에 갤러리 사정을 줄 때")
    inner class Context {

        @Test
        fun `촬영 순서는 폴더 정렬이 아니라 찍은 시각으로 매기고 폴더별 장수와 고른 장수를 싣는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 5)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(2))

            // when
            send(scene, AgentDecisionDto.Clarify("되묻기"), text = "  야외 폴더 어디야  ")

            // then
            val context = fakeDecisionClient.contexts.single()
            val shotOrderByName = context.concepts.associate { it.name to it.shotOrder }
            assertSoftly { softly ->
                softly.assertThat(fakeDecisionClient.questions.single()).isEqualTo("야외 폴더 어디야")
                softly.assertThat(shotOrderByName).isEqualTo(mapOf("야외" to 2, "화이트" to 1))
                softly.assertThat(context.concepts.first { it.name == "야외" }.timeRange).isEqualTo("15:00~18:00")
                softly.assertThat(context.concepts.first { it.name == "야외" }.details.map { it.name to it.photoCount })
                    .containsExactly("정원" to 3, "노을" to 1)
                softly.assertThat(context.selectedCount).isEqualTo(2)
                softly.assertThat(context.maxSelectablePhotoCount).isEqualTo(5)
            }
        }
    }

    @Nested
    @DisplayName("폴더를 찾을 때")
    inner class FindFolder {

        @Test
        fun `첫 후보 폴더를 열고 후보가 여럿이면 함께 돌려준다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val decision = AgentDecisionDto.FindFolder(
                listOf(
                    AgentDecisionDto.FolderTarget(conceptFolderId = scene.outdoorConceptId, detailFolderId = scene.sunsetDetailId),
                    AgentDecisionDto.FolderTarget(conceptFolderId = scene.outdoorConceptId),
                ),
            )

            // when
            val response = send(scene, decision)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.FIND_FOLDER)
                softly.assertThat(response.view?.detailFolderId).isEqualTo(scene.sunsetDetailId)
                softly.assertThat(response.folderCandidates?.map { it.name }).containsExactly("야외 > 노을", "야외")
            }
        }

        @Test
        fun `이 갤러리에 없는 폴더뿐이면 되묻는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val other = agentFixture.웨딩_갤러리()

            // when
            val response = send(scene, AgentDecisionDto.FindFolder(listOf(AgentDecisionDto.FolderTarget(other.outdoorConceptId))))

            // then
            assertThat(response.kind).isEqualTo(AgentReplyKind.CLARIFY)
        }
    }

    @Nested
    @DisplayName("사진을 거를 때")
    inner class FilterPhotos {

        @Test
        fun `조건에 맞는 사진 id를 필터와 함께 돌려주고 한 폴더면 그 폴더를 연다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val filter = PhotoFilter(conceptFolderIds = listOf(scene.outdoorConceptId), subjects = listOf(AgentSubject.GROOM))

            // when
            val response = send(scene, AgentDecisionDto.FilterPhotos(filter))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.FILTER_PHOTOS)
                softly.assertThat(response.view?.photoIds).containsExactly(scene.photoIds[4])
                softly.assertThat(response.view?.conceptFolderId).isEqualTo(scene.outdoorConceptId)
                softly.assertThat(response.view?.filter).isEqualTo(filter)
                softly.assertThat(response.reply).isEqualTo("조건에 맞는 사진 1장이에요.")
            }
        }

        @Test
        fun `연사 대표만 고르면 비슷한 컷 묶음마다 한 장만 남는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            val response = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(burstRepresentativeOnly = true)))

            // then
            assertThat(response.view?.photoIds).doesNotContain(scene.photoIds[1], scene.photoIds[6]).hasSize(7)
        }

        @Test
        fun `못 거르는 조건은 빼고 거른 뒤 답에서 못 한다고 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val decision = AgentDecisionDto.FilterPhotos(
                filter = PhotoFilter(subjects = listOf(AgentSubject.BRIDE)),
                unsupported = listOf(UnsupportedCondition.FACE),
            )

            // when
            val response = send(scene, decision)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.view?.photoIds).containsExactly(scene.photoIds[0], scene.photoIds[1])
                softly.assertThat(response.unsupported).containsExactly(UnsupportedCondition.FACE)
                softly.assertThat(response.reply).contains("표정·눈 감김")
            }
        }
    }

    @Nested
    @DisplayName("셀 때")
    inner class Stats {

        @Test
        fun `컨셉별로 세면 하나도 안 고른 컨셉도 0장으로 넣고 치우쳤다고 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(3))

            // when
            val response = send(scene, AgentDecisionDto.Stats(metric = StatsMetric.BY_CONCEPT, scope = StatsScope.SELECTED))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.stats?.rows?.map { it.label to it.count }).containsExactly("화이트" to 3, "야외" to 0)
                softly.assertThat(response.stats?.total).isEqualTo(3)
                softly.assertThat(response.reply).isEqualTo("고른 3장 중 화이트 3장 · 야외 0장이에요. 화이트에 많이 몰려 있고 야외이(가) 적어요.")
            }
        }

        @Test
        fun `갤러리 전체를 세부 폴더별로 세면 미분류를 맨 뒤에 붙인다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            val response = send(scene, AgentDecisionDto.Stats(metric = StatsMetric.BY_DETAIL, scope = StatsScope.GALLERY))

            // then
            assertThat(response.stats?.rows?.map { it.label to it.count })
                .containsExactly("화이트 > 화이트A" to 4, "야외 > 정원" to 3, "야외 > 노을" to 1, "미분류" to 1)
        }

        @Test
        fun `장수를 세면 계약 장수까지 남은 장수를 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 5)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(3))

            // when
            val response = send(scene, AgentDecisionDto.Stats(metric = StatsMetric.COUNT, scope = StatsScope.SELECTED))

            // then
            assertThat(response.reply).isEqualTo("지금 3장 골랐어요. 계약 5장까지 2장 남았어요.")
        }

        @Test
        fun `중복을 세면 같은 연사 묶음에서 두 장 이상 고른 묶음과 줄일 수 있는 장수를 센다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(2) + scene.photoIds[5])

            // when
            val response = send(scene, AgentDecisionDto.Stats(metric = StatsMetric.DUPLICATES, scope = StatsScope.SELECTED))

            // then
            assertThat(response.reply).isEqualTo("비슷한 연사 컷을 두 장 이상 고른 묶음이 1개예요. 묶음마다 한 장만 남기면 1장 줄일 수 있어요.")
        }
    }

    @Nested
    @DisplayName("실행 계획을 만들 때")
    inner class PlanAction {

        @Test
        fun `넣기는 이미 고른 사진을 빼고 계약 장수를 넘는 만큼을 미리 센다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 4)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(3))
            val decision = AgentDecisionDto.PlanAction(
                op = AgentActionOp.SELECT_ADD,
                filter = PhotoFilter(subjects = listOf(AgentSubject.COUPLE, AgentSubject.BRIDE)),
            )

            // when
            val response = send(scene, decision)

            // then
            val action = checkNotNull(response.action)
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.PLAN_ACTION)
                softly.assertThat(action.steps.single().photoIds)
                    .containsExactly(scene.photoIds[3], scene.photoIds[5], scene.photoIds[6], scene.photoIds[7])
                softly.assertThat(action.skippedPhotoCount).isEqualTo(2)
                softly.assertThat(action.overLimitBy).isEqualTo(3)
                softly.assertThat(response.reply).contains("4장을 선택에 넣을게요", "이미 그 상태인 2장은 뺐어요", "3장 넘어서 실행되지 않아요")
            }
        }

        @Test
        fun `옮기기는 이미 그 폴더에 있는 사진을 빼고 대상 폴더 이름을 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val decision = AgentDecisionDto.PlanAction(
                op = AgentActionOp.MOVE,
                filter = PhotoFilter(subjects = listOf(AgentSubject.COUPLE)),
                targetDetailFolderId = scene.sunsetDetailId,
            )

            // when
            val response = send(scene, decision)

            // then
            val step = checkNotNull(response.action).steps.single()
            assertSoftly { softly ->
                softly.assertThat(step.photoIds).containsExactly(scene.photoIds[3], scene.photoIds[5], scene.photoIds[6])
                softly.assertThat(step.targetDetailFolderId).isEqualTo(scene.sunsetDetailId)
                softly.assertThat(response.reply).startsWith("사진 3장을 '야외 > 노을'(으)로 옮길게요.")
            }
        }

        @Test
        fun `옮길 폴더가 이 갤러리에 없으면 계획 없이 되묻는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val other = agentFixture.웨딩_갤러리()
            val decision = AgentDecisionDto.PlanAction(op = AgentActionOp.MOVE, filter = PhotoFilter(), targetDetailFolderId = other.sunsetDetailId)

            // when
            val response = send(scene, decision)

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.CLARIFY)
                softly.assertThat(response.action).isNull()
            }
        }

        @Test
        fun `선택을 제출했으면 계획을 만들지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(1))
            selectionFixture.제출(selectionId, scene.memberId)

            // when
            val response = send(scene, AgentDecisionDto.PlanAction(op = AgentActionOp.SELECT_ADD, filter = PhotoFilter()))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.CLARIFY)
                softly.assertThat(response.reply).contains("이미 제출")
            }
        }
    }

    @Nested
    @DisplayName("묻는 사람과 판단기를 확인할 때")
    inner class Guard {

        @Test
        fun `작가는 묻지 못한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            fakeDecisionClient.decision = AgentDecisionDto.Clarify("되묻기")

            // when & then
            assertThatThrownBy {
                agentMessageService.send(scene.galleryId, scene.gallery.photographer.requiredId, AgentMessageRequest(text = "몇 장 골랐어?"))
            }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            assertThat(fakeDecisionClient.questions).isEmpty()
        }

        @Test
        fun `빈 질문은 판단기를 부르지 않고 거절한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when & then
            assertThatThrownBy { agentMessageService.send(scene.galleryId, scene.memberId, AgentMessageRequest(text = "   ")) }
                .isInstanceOf(AgentException::class.java)
                .extracting("errorCode")
                .isEqualTo(AgentErrorCode.EMPTY_QUESTION)
            assertThat(fakeDecisionClient.questions).isEmpty()
        }

        @Test
        fun `판단기가 답하지 못하면 그 실패를 그대로 알린다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            fakeDecisionClient.failure = AgentException(AgentErrorCode.DECISION_UNAVAILABLE)

            // when & then
            assertThatThrownBy { agentMessageService.send(scene.galleryId, scene.memberId, AgentMessageRequest(text = "몇 장 골랐어?")) }
                .isInstanceOf(AgentException::class.java)
                .extracting("errorCode")
                .isEqualTo(AgentErrorCode.DECISION_UNAVAILABLE)
        }
    }
}
