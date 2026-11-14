package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.domain.AgentActor
import com.soma.wes.agent.domain.AgentCheckCode
import com.soma.wes.agent.domain.AgentReplyKind
import com.soma.wes.agent.domain.AgentScreen
import com.soma.wes.agent.domain.AgentSubject
import com.soma.wes.agent.domain.AgentTurnRole
import com.soma.wes.agent.domain.ExplainTopic
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
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class AgentMessageServiceTest @Autowired constructor(
    private val agentMessageService: AgentMessageService,
    private val fakeDecisionClient: FakeDecisionClient,
    private val agentFixture: AgentFixture,
    private val selectionFixture: SelectionFixture,
    private val jdbcTemplate: JdbcTemplate,
) {

    @BeforeEach
    fun resetDecisionClient() {
        fakeDecisionClient.reset()
    }

    private fun send(
        scene: AgentScene,
        decision: AgentDecisionDto,
        text: String = "질문",
        history: List<AgentMessageRequest.Turn> = emptyList(),
    ): AgentMessageResponse {
        fakeDecisionClient.decision = decision
        return agentMessageService.send(scene.galleryId, scene.memberId, AgentMessageRequest(text = text, history = history))
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

        @Test
        fun `세부 폴더마다 사진 대부분의 피사체를 주고 섞였으면 비운다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            send(scene, AgentDecisionDto.Clarify("되묻기"))

            // then
            val subjectByDetail = fakeDecisionClient.contexts.single().concepts.flatMap { it.details }.associate { it.name to it.mainSubject }
            assertThat(subjectByDetail).isEqualTo(mapOf("화이트A" to null, "정원" to AgentSubject.COUPLE, "노을" to AgentSubject.COUPLE))
        }

        @Test
        fun `웹이 돌려보낸 최근 대화를 후보 폴더와 필터까지 판단 문맥으로 넘긴다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val filter = PhotoFilter(conceptFolderIds = listOf(scene.outdoorConceptId))
            val history = listOf(
                AgentMessageRequest.Turn(role = AgentTurnRole.USER, text = "노을 폴더 어디 있어?"),
                AgentMessageRequest.Turn(
                    role = AgentTurnRole.ASSISTANT,
                    text = "이 중 어느 폴더일까요? 1. 야외 > 정원 · 2. 야외 > 노을",
                    kind = AgentReplyKind.FIND_FOLDER,
                    folderCandidates = listOf(
                        AgentMessageRequest.FolderRef(conceptFolderId = scene.outdoorConceptId, detailFolderId = scene.gardenDetailId),
                        AgentMessageRequest.FolderRef(conceptFolderId = scene.outdoorConceptId, detailFolderId = scene.sunsetDetailId),
                    ),
                    filter = filter,
                ),
            )

            // when
            send(scene, AgentDecisionDto.Clarify("되묻기"), text = "두 번째 거 열어줘", history = history)

            // then
            val turns = fakeDecisionClient.contexts.single().history
            assertSoftly { softly ->
                softly.assertThat(turns.map { it.role }).containsExactly(AgentTurnRole.USER, AgentTurnRole.ASSISTANT)
                softly.assertThat(turns.last().folderCandidates.map { it.detailFolderId }).containsExactly(scene.gardenDetailId, scene.sunsetDetailId)
                softly.assertThat(turns.last().filter).isEqualTo(filter)
            }
        }

        @Test
        fun `최근 대화가 모양을 어기면 판단하지 않고 거절한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val user = AgentMessageRequest.Turn(role = AgentTurnRole.USER, text = "질문")
            val assistant = AgentMessageRequest.Turn(role = AgentTurnRole.ASSISTANT, text = "답")
            val invalidHistories = listOf(
                listOf(user),
                listOf(assistant, user),
                List(4) { listOf(user, assistant) }.flatten(),
                listOf(user.copy(text = "가".repeat(AgentMessageRequest.MAX_TEXT_LENGTH + 1)), assistant),
            )

            // when & then
            for (history in invalidHistories) {
                assertThatThrownBy { send(scene, AgentDecisionDto.Clarify("되묻기"), history = history) }
                    .isInstanceOf(AgentException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(AgentErrorCode.INVALID_HISTORY)
            }
            assertThat(fakeDecisionClient.contexts).isEmpty()
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
        fun `가족이 좋아한 사진만 고르면 하객 좋아요가 있는 사진만 남는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            agentFixture.반응_남기기(scene)

            // when
            val response = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(likedByGuests = true)))

            // then
            val reactions = response.view?.reactions.orEmpty().associateBy { it.photoId }
            assertSoftly { softly ->
                softly.assertThat(response.view?.photoIds).containsExactly(scene.photoIds[0], scene.photoIds[3])
                softly.assertThat(response.reply).endsWith("사진마다 남긴 별점·메모·댓글·좋아요를 같이 보여 드릴게요.")
                softly.assertThat(reactions.keys).containsExactly(scene.photoIds[0], scene.photoIds[3])
                softly.assertThat(reactions[scene.photoIds[0]]?.rating).isEqualTo(5)
                softly.assertThat(reactions[scene.photoIds[0]]?.likedBy).containsExactly("어머니")
                softly.assertThat(reactions[scene.photoIds[3]]?.comments)
                    .containsExactly(AgentMessageResponse.PhotoReactions.Comment(author = "어머니", content = "이 사진이 제일 예뻐요"))
            }
        }

        @Test
        fun `반응 조건이 아니면 사진 목록만 주고 반응 본문은 싣지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            agentFixture.반응_남기기(scene)

            // when
            val response = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(subjects = listOf(AgentSubject.BRIDE))))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.view?.reactions).isNull()
                softly.assertThat(response.reply).isEqualTo("조건에 맞는 사진 2장이에요.")
            }
        }

        @Test
        fun `별점 이상·댓글·메모 조건은 각각 그 반응이 있는 사진만 남긴다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            agentFixture.반응_남기기(scene)

            // when
            val rated = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(minRating = 4)))
            val commented = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(hasComments = true)))
            val withoutMemo = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(hasMemo = false)))

            // then
            assertSoftly { softly ->
                softly.assertThat(rated.view?.photoIds).containsExactly(scene.photoIds[0], scene.photoIds[3])
                softly.assertThat(commented.view?.photoIds).containsExactly(scene.photoIds[2], scene.photoIds[3])
                softly.assertThat(commented.view?.reactions?.first { it.photoId == scene.photoIds[2] }?.comments?.single()?.content).isEqualTo("이거 어때?")
                softly.assertThat(withoutMemo.view?.photoIds).doesNotContain(scene.photoIds[1]).hasSize(8)
            }
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
                softly.assertThat(response.reply).contains("눈 감김·표정")
            }
        }
    }

    @Nested
    @DisplayName("사용법·상태를 물을 때")
    inner class Explain {

        @Test
        fun `서비스 개념 주제는 서버가 정한 문장으로 답한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val topics = listOf(
                ExplainTopic.AI_FOLDERS,
                ExplainTopic.FOLDER_LEVELS,
                ExplainTopic.BURST,
                ExplainTopic.SUBJECTS,
                ExplainTopic.EXCLUDED_PHOTOS,
                ExplainTopic.GALLERY_TYPE,
            )

            // when
            val replies = topics.associateWith { topic -> send(scene, AgentDecisionDto.Explain(topic)).reply }

            // then
            assertSoftly { softly ->
                softly.assertThat(replies.values).allSatisfy { reply -> assertThat(reply).isNotBlank().doesNotContain("모르는 내용") }
                softly.assertThat(replies[ExplainTopic.GALLERY_TYPE]).startsWith("여기는 작가님이 사진을 올리고 두 분을 초대한 스튜디오 갤러리예요.")
                softly.assertThat(replies[ExplainTopic.EXCLUDED_PHOTOS]).contains("원본은 지워지지 않고")
            }
        }


        @Test
        fun `남은 장수를 물으면 계약 장수와 고른 장수로 답한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 10)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(3))

            // when
            val response = send(scene, AgentDecisionDto.Explain(ExplainTopic.MAX_PHOTOS))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.EXPLAIN)
                softly.assertThat(response.reply).isEqualTo("계약 장수는 10장이고 지금 3장 골라 7장 더 고를 수 있어요. 장수를 늘리고 싶으면 '계약 장수 상향 요청'으로 작가님께 요청할 수 있어요.")
                softly.assertThat(response.explain?.topic).isEqualTo(ExplainTopic.MAX_PHOTOS)
                softly.assertThat(response.explain?.links).isEmpty()
            }
        }

        @Test
        fun `제출을 물으면 계약 장수를 맞췄는지와 함께 셀렉 화면 버튼을 준다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 3)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), scene.photoIds.take(3))

            // when
            val response = send(scene, AgentDecisionDto.Explain(ExplainTopic.SUBMIT))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.reply).contains("지금 3장으로 계약 장수를 딱 맞춰 제출할 수 있어요")
                softly.assertThat(response.explain?.links?.map { it.to }).containsExactly(AgentScreen.SELECTION)
            }
        }

        @Test
        fun `보정 회차를 물으면 계약 횟수와 쓴 횟수로 답한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxRetouchRoundCount = 3)

            // when
            val response = send(scene, AgentDecisionDto.Explain(ExplainTopic.RETOUCH_ROUNDS))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.reply).startsWith("계약 보정 3회 중 0회를 썼어요. 보정은 셀렉을 제출한 뒤")
                softly.assertThat(response.explain?.links?.map { it.to }).containsExactly(AgentScreen.RETOUCH)
            }
        }

        @Test
        fun `마감이 없으면 없다고 말하고 모르는 주제는 작가에게 돌린다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            val deadline = send(scene, AgentDecisionDto.Explain(ExplainTopic.DEADLINE))
            val unknown = send(scene, AgentDecisionDto.Explain(ExplainTopic.UNKNOWN))

            // then
            assertSoftly { softly ->
                softly.assertThat(deadline.reply).isEqualTo("정해진 선택 마감이 없어요. 작가님이 정하면 여기서 알려 드릴게요.")
                softly.assertThat(unknown.reply).contains("모르는 내용")
                softly.assertThat(unknown.explain?.links).isEmpty()
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
        fun `별점별로 세면 5개부터 1개까지 넣고 별점 없는 사진은 맨 뒤에 붙인다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            agentFixture.반응_남기기(scene)

            // when
            val response = send(scene, AgentDecisionDto.Stats(metric = StatsMetric.BY_RATING, scope = StatsScope.GALLERY))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.stats?.rows?.map { it.label to it.count })
                    .containsExactly("별 5개" to 1, "별 4개" to 1, "별 3개" to 0, "별 2개" to 1, "별 1개" to 0, "별점 없음" to 6)
                softly.assertThat(response.reply).doesNotContain("몰려").doesNotContain("고르게")
            }
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
    @DisplayName("폴더 이름 바꾸기를 계획할 때")
    inner class PlanRename {

        @Test
        fun `계획 순간의 이름을 예상 이름으로 담고 아직 바꾸지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val target = AgentDecisionDto.FolderTarget(conceptFolderId = scene.outdoorConceptId, detailFolderId = scene.sunsetDetailId)

            // when
            val response = send(scene, AgentDecisionDto.PlanRename(target = target, name = "석양"))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.PLAN_ACTION)
                softly.assertThat(response.reply).isEqualTo("'노을' 폴더 이름을 '석양'(으)로 바꿀게요.")
                softly.assertThat(response.action?.steps?.single()).isEqualTo(
                    AgentActionStep(
                        op = AgentActionOp.RENAME_DETAIL,
                        conceptFolderId = scene.outdoorConceptId,
                        detailFolderId = scene.sunsetDetailId,
                        name = "석양",
                        expectedName = "노을",
                    ),
                )
                softly.assertThat(response.view?.detailFolderId).isEqualTo(scene.sunsetDetailId)
            }
        }

        @Test
        fun `이미 그 이름이면 계획 없이 알려 준다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val target = AgentDecisionDto.FolderTarget(conceptFolderId = scene.outdoorConceptId)

            // when
            val response = send(scene, AgentDecisionDto.PlanRename(target = target, name = "야외"))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.CLARIFY)
                softly.assertThat(response.action).isNull()
            }
        }
    }

    @Nested
    @DisplayName("제출 전에 점검할 때")
    inner class Check {

        @Test
        fun `걸린 항목만 정해 둔 순서로 세고 숫자는 같은 상태의 SQL과 같다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 2)
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, listOf(scene.photoIds[0], scene.photoIds[1], scene.photoIds[8]))
            agentFixture.별점(scene, 8, scene.memberId, score = 1)

            // when
            val response = send(scene, AgentDecisionDto.Check())

            // then
            val items = response.check?.items.orEmpty()
            val lowRatedBySql = checkNotNull(jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM photo_selection_items i JOIN photo_ratings r ON r.photo_id = i.photo_id
                WHERE i.selection_id = ? AND r.score = 1
                """.trimIndent(),
                Int::class.java,
                selectionId,
            )) { "count(*)는 늘 한 행이다" }
            assertSoftly { softly ->
                softly.assertThat(response.kind).isEqualTo(AgentReplyKind.CHECK)
                softly.assertThat(items.map { it.code to it.count }).containsExactly(
                    AgentCheckCode.OVER_LIMIT to 1,
                    AgentCheckCode.EMPTY_CONCEPT to 1,
                    AgentCheckCode.BURST_DUPLICATE to 2,
                    AgentCheckCode.LOW_RATED_SELECTED to lowRatedBySql,
                )
                softly.assertThat(items[1].conceptFolderIds).containsExactly(scene.outdoorConceptId)
                softly.assertThat(items[2].photoIds).containsExactly(scene.photoIds[0], scene.photoIds[1])
                softly.assertThat(response.check?.selectedCount).isEqualTo(3)
                softly.assertThat(response.reply).isEqualTo(
                    """
                    제출 전에 볼 것 4가지예요.
                    1. 계약 2장보다 1장 많아요.
                    2. '야외' 컨셉에서 고른 사진이 없어요.
                    3. 같은 연사에서 2장 이상 고른 묶음이 1개(2장)예요.
                    4. 1점 준 사진 1장이 선택에 담겨 있어요.
                    """.trimIndent(),
                )
            }
        }

        @Test
        fun `걸리는 것이 없으면 고른 장수와 함께 그렇다고 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 5)
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), listOf(scene.photoIds[3], scene.photoIds[5]))

            // when
            val response = send(scene, AgentDecisionDto.Check())

            // then
            assertSoftly { softly ->
                softly.assertThat(response.check?.items).isEmpty()
                softly.assertThat(response.reply).isEqualTo("고른 2장, 제출 전에 걸리는 것이 없어요.")
            }
        }

        @Test
        fun `한 항목을 가리키면 지금 상태로 다시 세어 그 사진을 연다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            selectionFixture.담긴_사진(selectionFixture.셀렉(scene.galleryId), listOf(scene.photoIds[0], scene.photoIds[1]))

            // when
            val response = send(scene, AgentDecisionDto.Check(focus = AgentCheckCode.BURST_DUPLICATE))
            val missing = send(scene, AgentDecisionDto.Check(focus = AgentCheckCode.LOW_RATED_SELECTED))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.view?.photoIds).containsExactly(scene.photoIds[0], scene.photoIds[1])
                softly.assertThat(response.reply).isEqualTo("같은 연사에서 2장 이상 고른 묶음이 1개(2장)예요.")
                softly.assertThat(missing.kind).isEqualTo(AgentReplyKind.CLARIFY)
            }
        }

        @Test
        fun `고른 사진이 없으면 점검하지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            val response = send(scene, AgentDecisionDto.Check())

            // then
            assertThat(response.reply).isEqualTo("아직 고른 사진이 없어요. 사진을 고른 뒤에 점검해 드릴게요.")
        }
    }

    @Nested
    @DisplayName("누가 했는지로 거를 때")
    inner class Actor {

        @Test
        fun `내가 담은 사진과 배우자가 담은 사진을 묻는 사람 기준으로 가른다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val partnerId = agentFixture.배우자(scene)
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(2), addedByUserId = scene.memberId)
            selectionFixture.담긴_사진(selectionId, listOf(scene.photoIds[2]), addedByUserId = partnerId, sortOrderFrom = 2)

            // when
            val mine = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(addedBy = AgentActor.ME)))
            val partners = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(addedBy = AgentActor.PARTNER)))

            // then
            assertSoftly { softly ->
                softly.assertThat(mine.view?.photoIds).containsExactly(scene.photoIds[0], scene.photoIds[1])
                softly.assertThat(partners.view?.photoIds).containsExactly(scene.photoIds[2])
            }
        }

        @Test
        fun `배우자가 준 1점은 마지막으로 매긴 사람이 배우자인 1점 사진만이다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val partnerId = agentFixture.배우자(scene)
            agentFixture.별점(scene, 0, scene.memberId, score = 1)
            agentFixture.별점(scene, 1, partnerId, score = 1)
            agentFixture.별점(scene, 2, partnerId, score = 5)
            agentFixture.별점(scene, 3, partnerId, score = 1)
            agentFixture.별점(scene, 3, scene.memberId, score = 1)
            val filter = PhotoFilter(ratedBy = AgentActor.PARTNER, minRating = 1, maxRating = 1)

            // when
            val response = send(scene, AgentDecisionDto.FilterPhotos(filter))

            // then
            assertSoftly { softly ->
                softly.assertThat(response.view?.photoIds).containsExactly(scene.photoIds[1])
                softly.assertThat(response.view?.reactions?.map { it.photoId }).containsExactly(scene.photoIds[1])
            }
        }

        @Test
        fun `배우자가 댓글 단 사진과 내가 메모한 사진을 거른다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val partnerId = agentFixture.배우자(scene)
            agentFixture.댓글(scene, 4, partnerId)
            agentFixture.댓글(scene, 5, scene.memberId)
            agentFixture.메모(scene, 6, scene.memberId)
            agentFixture.메모(scene, 7, partnerId)

            // when
            val commented = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(commentedBy = AgentActor.PARTNER)))
            val memo = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(memoBy = AgentActor.ME)))

            // then
            assertSoftly { softly ->
                softly.assertThat(commented.view?.photoIds).containsExactly(scene.photoIds[4])
                softly.assertThat(memo.view?.photoIds).containsExactly(scene.photoIds[6])
            }
        }

        @Test
        fun `배우자가 아직 없으면 0장이라 답하지 않고 그 까닭을 말한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when
            val filtered = send(scene, AgentDecisionDto.FilterPhotos(PhotoFilter(addedBy = AgentActor.PARTNER)))
            val counted = send(scene, AgentDecisionDto.Stats(StatsMetric.COUNT, StatsScope.GALLERY, PhotoFilter(ratedBy = AgentActor.PARTNER)))

            // then
            assertSoftly { softly ->
                softly.assertThat(filtered.kind).isEqualTo(AgentReplyKind.CLARIFY)
                softly.assertThat(filtered.reply).contains("배우자분이 아직 갤러리에 들어오지 않아서")
                softly.assertThat(counted.kind).isEqualTo(AgentReplyKind.CLARIFY)
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
