package com.soma.wes.agent.service

import com.soma.wes.agent.domain.AgentActionOp
import com.soma.wes.agent.domain.AgentActionStep
import com.soma.wes.agent.dto.request.AgentActionRequest
import com.soma.wes.agent.exception.AgentErrorCode
import com.soma.wes.agent.exception.AgentException
import com.soma.wes.agent.fixture.AgentFixture
import com.soma.wes.agent.fixture.AgentScene
import com.soma.wes.folder.dto.request.RenameFolderRequest
import com.soma.wes.folder.repository.ConceptFolderRepository
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.service.FolderService
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.selection.exception.SelectionException
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.selection.repository.PhotoSelectionItemRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class AgentActionServiceTest @Autowired constructor(
    private val agentActionService: AgentActionService,
    private val agentFixture: AgentFixture,
    private val selectionFixture: SelectionFixture,
    private val photoSelectionItemRepository: PhotoSelectionItemRepository,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val conceptFolderRepository: ConceptFolderRepository,
    private val detailFolderRepository: DetailFolderRepository,
    private val folderService: FolderService,
) {

    private fun execute(scene: AgentScene, vararg steps: AgentActionStep) =
        agentActionService.execute(scene.galleryId, scene.memberId, AgentActionRequest(steps = steps.toList()))

    private fun selectedPhotoIds(selectionId: Long): Set<Long> =
        photoSelectionItemRepository.findAllBySelectionId(selectionId).map { it.photoId }.toSet()

    private fun folderByPhotoId(scene: AgentScene): Map<Long, Long?> {
        val assigned = assignmentRepository.findAllByGalleryIdAndPhotoIdIn(scene.galleryId, scene.photoIds)
            .associate { it.photoId to it.detailFolderId }
        return scene.photoIds.associateWith { assigned[it] }
    }

    @Nested
    @DisplayName("선택에 넣고 뺄 때")
    inner class Selection {

        @Test
        fun `넣기는 이미 담긴 사진을 건너뛰고 되돌리면 새로 넣은 것만 빠진다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(1))
            val before = selectedPhotoIds(selectionId)

            // when
            val result = execute(scene, AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(3)))

            // then
            assertSoftly { softly ->
                softly.assertThat(result.applied.single().photoIds).containsExactly(scene.photoIds[1], scene.photoIds[2])
                softly.assertThat(selectedPhotoIds(selectionId)).containsExactlyInAnyOrderElementsOf(scene.photoIds.take(3))
                softly.assertThat(result.undo.steps.single())
                    .isEqualTo(AgentActionStep(op = AgentActionOp.SELECT_REMOVE, photoIds = listOf(scene.photoIds[1], scene.photoIds[2])))
            }

            // when
            agentActionService.execute(scene.galleryId, scene.memberId, result.undo)

            // then
            assertThat(selectedPhotoIds(selectionId)).isEqualTo(before)
        }

        @Test
        fun `빼기를 되돌리면 뺀 사진이 다시 담긴다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(4))

            // when
            val result = execute(scene, AgentActionStep(op = AgentActionOp.SELECT_REMOVE, photoIds = scene.photoIds.subList(2, 6)))
            val afterRemove = selectedPhotoIds(selectionId)
            agentActionService.execute(scene.galleryId, scene.memberId, result.undo)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.applied.single().photoIds).containsExactly(scene.photoIds[2], scene.photoIds[3])
                softly.assertThat(afterRemove).containsExactlyInAnyOrder(scene.photoIds[0], scene.photoIds[1])
                softly.assertThat(selectedPhotoIds(selectionId)).containsExactlyInAnyOrderElementsOf(scene.photoIds.take(4))
            }
        }

        @Test
        fun `계약 장수를 넘기면 기존 규칙대로 거절되고 아무것도 담기지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 2)
            val selectionId = selectionFixture.셀렉(scene.galleryId)

            // when & then
            assertThatThrownBy { execute(scene, AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(3))) }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.MAX_SELECTABLE_PHOTO_COUNT_EXCEEDED)
            assertThat(selectedPhotoIds(selectionId)).isEmpty()
        }

        @Test
        fun `제출한 선택은 바꾸지 못한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(1))
            selectionFixture.제출(selectionId, scene.memberId)

            // when & then
            assertThatThrownBy { execute(scene, AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.subList(1, 2))) }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
        }
    }

    @Nested
    @DisplayName("폴더로 옮길 때")
    inner class Move {

        @Test
        fun `여러 폴더에서 옮긴 것을 되돌리면 각자 원래 폴더로 돌아간다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val before = folderByPhotoId(scene)
            val moving = listOf(scene.photoIds[0], scene.photoIds[4], scene.photoIds[7], scene.photoIds[8])

            // when
            val result = execute(scene, AgentActionStep(op = AgentActionOp.MOVE, photoIds = moving, targetDetailFolderId = scene.sunsetDetailId))
            val afterMove = folderByPhotoId(scene)
            agentActionService.execute(scene.galleryId, scene.memberId, result.undo)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.applied.single().photoIds).containsExactly(scene.photoIds[0], scene.photoIds[4], scene.photoIds[8])
                softly.assertThat(moving.map { afterMove[it] }).containsOnly(scene.sunsetDetailId)
                softly.assertThat(result.undo.steps.map { it.targetDetailFolderId })
                    .containsExactlyInAnyOrder(scene.whiteDetailId, scene.gardenDetailId, null)
                softly.assertThat(folderByPhotoId(scene)).isEqualTo(before)
            }
        }
    }

    @Nested
    @DisplayName("폴더 이름을 바꿀 때")
    inner class Rename {

        private fun conceptName(scene: AgentScene): String? = conceptFolderRepository.findByIdAndGalleryId(scene.outdoorConceptId, scene.galleryId)?.name

        private fun renameOutdoor(scene: AgentScene, name: String, expectedName: String) =
            AgentActionStep(op = AgentActionOp.RENAME_CONCEPT, conceptFolderId = scene.outdoorConceptId, name = name, expectedName = expectedName)

        @Test
        fun `컨셉 이름을 바꾸고 되돌리면 원래 이름이 된다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val step = renameOutdoor(scene, name = "가든", expectedName = "야외")

            // when
            val result = execute(scene, step)

            // then
            assertSoftly { softly ->
                softly.assertThat(conceptName(scene)).isEqualTo("가든")
                softly.assertThat(result.applied.single()).isEqualTo(step)
                softly.assertThat(result.undo.steps.single()).isEqualTo(step.copy(name = "야외", expectedName = "가든"))
            }

            // when
            agentActionService.execute(scene.galleryId, scene.memberId, result.undo)

            // then
            assertThat(conceptName(scene)).isEqualTo("야외")
        }

        @Test
        fun `세부 폴더 이름을 바꾼다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val step = AgentActionStep(
                op = AgentActionOp.RENAME_DETAIL,
                conceptFolderId = scene.outdoorConceptId,
                detailFolderId = scene.sunsetDetailId,
                name = "석양",
                expectedName = "노을",
            )

            // when
            execute(scene, step)

            // then
            assertThat(detailFolderRepository.findByIdAndGalleryId(scene.sunsetDetailId, scene.galleryId)?.name).isEqualTo("석양")
        }

        @Test
        fun `바꾼 뒤 배우자가 다시 바꿨으면 되돌리기가 배우자 이름을 덮지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val partnerId = agentFixture.배우자(scene)
            val result = execute(scene, renameOutdoor(scene, name = "가든", expectedName = "야외"))
            folderService.renameConcept(scene.galleryId, scene.outdoorConceptId, partnerId, RenameFolderRequest(name = "정원 스냅"))

            // when
            val undone = agentActionService.execute(scene.galleryId, scene.memberId, result.undo)

            // then
            assertSoftly { softly ->
                softly.assertThat(undone.applied).isEmpty()
                softly.assertThat(undone.undo.steps).isEmpty()
                softly.assertThat(conceptName(scene)).isEqualTo("정원 스냅")
            }
        }

        @Test
        fun `계획 뒤 확인 전에 배우자가 바꿨으면 실행하지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val partnerId = agentFixture.배우자(scene)
            val planned = renameOutdoor(scene, name = "가든", expectedName = "야외")
            folderService.renameConcept(scene.galleryId, scene.outdoorConceptId, partnerId, RenameFolderRequest(name = "정원 스냅"))

            // when
            val result = execute(scene, planned)

            // then
            assertSoftly { softly ->
                softly.assertThat(result.applied).isEmpty()
                softly.assertThat(conceptName(scene)).isEqualTo("정원 스냅")
            }
        }

        @Test
        fun `제출한 초대 부부는 사람이 버튼으로 할 때처럼 거절된다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            selectionFixture.담긴_사진(selectionId, scene.photoIds.take(1))
            selectionFixture.제출(selectionId, scene.memberId)

            // when & then
            assertThatThrownBy { execute(scene, renameOutdoor(scene, name = "가든", expectedName = "야외")) }
                .isInstanceOf(SelectionException::class.java)
                .extracting("errorCode")
                .isEqualTo(SelectionErrorCode.SELECTION_ALREADY_SUBMITTED)
            assertThat(conceptName(scene)).isEqualTo("야외")
        }

        @Test
        fun `사진을 실은 이름 바꾸기와 폴더 칸을 실은 사진 동작은 거절한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val renameWithPhotos = renameOutdoor(scene, name = "가든", expectedName = "야외")
                .copy(photoIds = scene.photoIds.take(1))
            val addWithName = AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(1), name = "가든")

            // when & then
            for (step in listOf(renameWithPhotos, addWithName)) {
                assertThatThrownBy { execute(scene, step) }
                    .isInstanceOf(AgentException::class.java)
                    .extracting("errorCode")
                    .isEqualTo(AgentErrorCode.INVALID_ACTION)
            }
        }
    }

    @Nested
    @DisplayName("요청을 확인할 때")
    inner class Guard {

        @Test
        fun `빈 계획은 거절한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()

            // when & then
            assertThatThrownBy { execute(scene) }
                .isInstanceOf(AgentException::class.java)
                .extracting("errorCode")
                .isEqualTo(AgentErrorCode.EMPTY_ACTION)
        }

        @Test
        fun `이동이 아닌 동작에 대상 폴더가 있으면 거절한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val step = AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(1), targetDetailFolderId = scene.sunsetDetailId)

            // when & then
            assertThatThrownBy { execute(scene, step) }
                .isInstanceOf(AgentException::class.java)
                .extracting("errorCode")
                .isEqualTo(AgentErrorCode.INVALID_ACTION)
        }

        @Test
        fun `뒤 동작이 거절되면 앞 동작도 남지 않는다`() {
            // given
            val scene = agentFixture.웨딩_갤러리(maxSelectablePhotoCount = 1)
            val selectionId = selectionFixture.셀렉(scene.galleryId)
            val before = folderByPhotoId(scene)

            // when & then
            assertThatThrownBy {
                execute(
                    scene,
                    AgentActionStep(op = AgentActionOp.MOVE, photoIds = scene.photoIds.take(2), targetDetailFolderId = scene.sunsetDetailId),
                    AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(2)),
                )
            }.isInstanceOf(SelectionException::class.java)
            assertSoftly { softly ->
                softly.assertThat(folderByPhotoId(scene)).isEqualTo(before)
                softly.assertThat(selectedPhotoIds(selectionId)).isEmpty()
            }
        }

        @Test
        fun `작가는 도우미로 실행하지 못한다`() {
            // given
            val scene = agentFixture.웨딩_갤러리()
            val request = AgentActionRequest(steps = listOf(AgentActionStep(op = AgentActionOp.SELECT_ADD, photoIds = scene.photoIds.take(1))))

            // when & then
            assertThatThrownBy { agentActionService.execute(scene.galleryId, scene.gallery.photographer.requiredId, request) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode")
                .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
    }
}
