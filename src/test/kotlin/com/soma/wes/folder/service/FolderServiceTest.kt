package com.soma.wes.folder.service

import com.soma.wes.folder.domain.FolderName
import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.request.RenameFolderRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class FolderServiceTest @Autowired constructor(
    private val folderService: FolderService,
    private val galleryFixture: GalleryFixture,
    private val personalGalleryFixture: PersonalGalleryFixture,
    private val galleryRepository: GalleryRepository,
    private val photoFixture: PhotoFixture,
    private val assignmentRepository: DetailFolderAssignmentRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Nested
    @DisplayName("폴더를 만들 때")
    inner class Create {

        /** 폴더를 지워도 남은 폴더의 sortOrder는 당겨지지 않는다 — 개수로 정렬 순서를 정하면 새 폴더가 기존 폴더 사이에 끼었다. */
        @Test
        fun `앞쪽 폴더를 지운 뒤에도 새 폴더는 맨 뒤에 붙는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val (first, second, third, fourth) = listOf("A", "B", "C", "D").map { name ->
                folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest(name))
            }
            folderService.deleteConcept(fixture.galleryId, first.id, userId)
            folderService.deleteConcept(fixture.galleryId, second.id, userId)

            // when
            folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("E"))

            // then
            assertThat(folderService.list(fixture.galleryId, userId).map { it.name })
                .containsExactly(third.name, fourth.name, "E")
        }

        @Test
        fun `앞쪽 세부 폴더를 지운 뒤에도 새 세부 폴더는 맨 뒤에 붙는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("야외"))
            val (first, second, third, fourth) = listOf("A", "B", "C", "D").map { name ->
                folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest(name))
            }
            folderService.deleteDetail(fixture.galleryId, concept.id, first.id, userId)
            folderService.deleteDetail(fixture.galleryId, concept.id, second.id, userId)

            // when
            folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("E"))

            // then
            assertThat(folderService.list(fixture.galleryId, userId).single().details.map { it.name })
                .containsExactly(third.name, fourth.name, "E")
        }
    }

    @Nested
    @DisplayName("폴더 이름을 바꿀 때")
    inner class Rename {

        @Test
        fun `컨셉과 세부 폴더 이름만 바뀌고 사진 배정과 출처는 그대로다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 2)
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val detail = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(photoIds, detail.id))

            // when
            val renamedConcept = folderService.renameConcept(fixture.galleryId, concept.id, userId, RenameFolderRequest("  예식  "))
            val renamedDetail = folderService.renameDetail(
                fixture.galleryId, concept.id, detail.id, fixture.member.requiredId, RenameFolderRequest("신부 입장"),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(renamedConcept.name).isEqualTo("예식")
                softly.assertThat(renamedConcept.details.single().photoIds).containsExactlyInAnyOrderElementsOf(photoIds)
                softly.assertThat(renamedDetail.name).isEqualTo("신부 입장")
                softly.assertThat(renamedDetail.photoIds).containsExactlyInAnyOrderElementsOf(photoIds)
                softly.assertThat(folderService.list(fixture.galleryId, userId).single().details.single().name).isEqualTo("신부 입장")
                softly.assertThat(renamedConcept.createdSource).isEqualTo(FolderSource.USER)
            }
        }

        @Test
        fun `빈 이름과 100자를 넘는 이름은 거절한다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val detail = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장"))

            // when & then
            for (name in listOf("   ", "가".repeat(FolderName.MAX_LENGTH + 1))) {
                assertThatThrownBy { folderService.renameConcept(fixture.galleryId, concept.id, userId, RenameFolderRequest(name)) }
                    .isInstanceOf(FolderException::class.java)
                    .extracting("errorCode").isEqualTo(FolderErrorCode.INVALID_FOLDER_NAME)
                assertThatThrownBy { folderService.renameDetail(fixture.galleryId, concept.id, detail.id, userId, RenameFolderRequest(name)) }
                    .isInstanceOf(FolderException::class.java)
                    .extracting("errorCode").isEqualTo(FolderErrorCode.INVALID_FOLDER_NAME)
            }
            assertThat(folderService.list(fixture.galleryId, userId).single().name).isEqualTo("본식")
        }

        @Test
        fun `다른 갤러리의 컨셉과 다른 컨셉의 세부 폴더는 찾을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val first = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val second = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("야외"))
            val detail = folderService.createDetail(fixture.galleryId, first.id, userId, CreateDetailFolderRequest("입장"))
            val otherConcept = folderService.createConcept(other.galleryId, other.photographer.requiredId, CreateConceptFolderRequest("남의 컨셉"))

            // when & then
            assertThatThrownBy { folderService.renameConcept(fixture.galleryId, otherConcept.id, userId, RenameFolderRequest("변경")) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode").isEqualTo(FolderErrorCode.CONCEPT_NOT_FOUND)
            assertThatThrownBy { folderService.renameDetail(fixture.galleryId, second.id, detail.id, userId, RenameFolderRequest("변경")) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode").isEqualTo(FolderErrorCode.DETAIL_NOT_FOUND)
        }

        @Test
        fun `개인 갤러리 부부는 폴더를 확정한 뒤에도 바꾸고 마무리한 갤러리는 바꿀 수 없다`() {
            // given
            val personal = personalGalleryFixture.파트너와_개인_갤러리()
            val concept = folderService.createConcept(personal.galleryId, personal.ownerId, CreateConceptFolderRequest("본식"))
            jdbcTemplate.update("UPDATE galleries SET stage = 'SELECTION_IN_PROGRESS' WHERE id = ?", personal.galleryId)

            // when
            val renamed = folderService.renameConcept(personal.galleryId, concept.id, personal.partnerId, RenameFolderRequest("예식"))
            galleryRepository.saveAndFlush(galleryRepository.findById(personal.galleryId).orElseThrow().apply { close() })

            // then
            assertThat(renamed.name).isEqualTo("예식")
            assertThatThrownBy { folderService.renameConcept(personal.galleryId, concept.id, personal.ownerId, RenameFolderRequest("변경")) }
                .isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
        }
    }

    @Nested
    @DisplayName("세부 폴더를 합칠 때")
    inner class MergeDetail {

        @Test
        fun `원본의 사진이 모두 대상으로 옮겨지고 빈 원본 폴더는 사라진다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.member.requiredId
            val (sourcePhotos, targetPhotos) = photoFixture.업로드된_사진(fixture.galleryId, 3).let { it.take(2) to it.drop(2) }
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val source = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장 1"))
            val target = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장 2"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(sourcePhotos, source.id))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(targetPhotos, target.id))

            // when
            val merged = folderService.mergeDetail(
                fixture.galleryId, concept.id, source.id, userId, MergeDetailFolderRequest(target.id),
            )

            // then
            val details = folderService.list(fixture.galleryId, userId).single().details
            val assignments = assignmentRepository.findAllByPhotoIdIn(sourcePhotos)
            assertSoftly { softly ->
                softly.assertThat(merged.target.id).isEqualTo(target.id)
                softly.assertThat(merged.target.photoIds).containsExactlyInAnyOrderElementsOf(sourcePhotos + targetPhotos)
                softly.assertThat(details.map { it.id }).containsExactly(target.id)
                softly.assertThat(details.single().photoIds).containsExactlyInAnyOrderElementsOf(sourcePhotos + targetPhotos)
                softly.assertThat(assignments.map { it.assignedSource }).containsOnly(FolderSource.USER)
                softly.assertThat(assignments.map { it.assignedByUserId }).containsOnly(userId)
            }
        }

        @Test
        fun `다른 컨셉의 세부 폴더로 합칠 수 있다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 2)
            val ceremony = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val reception = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("피로연"))
            val source = folderService.createDetail(fixture.galleryId, ceremony.id, userId, CreateDetailFolderRequest("행진"))
            val target = folderService.createDetail(fixture.galleryId, reception.id, userId, CreateDetailFolderRequest("입장"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(photoIds, source.id))

            // when
            folderService.mergeDetail(fixture.galleryId, ceremony.id, source.id, userId, MergeDetailFolderRequest(target.id))

            // then
            val concepts = folderService.list(fixture.galleryId, userId)
            assertThat(concepts[0].details).isEmpty()
            assertThat(concepts[1].details.single().photoIds).containsExactlyInAnyOrderElementsOf(photoIds)
        }

        @Test
        fun `같은 폴더끼리 합치면 거절하고 아무것도 바꾸지 않는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 1)
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val detail = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(photoIds, detail.id))

            // when & then
            assertThatThrownBy {
                folderService.mergeDetail(fixture.galleryId, concept.id, detail.id, userId, MergeDetailFolderRequest(detail.id))
            }.isInstanceOf(FolderException::class.java).extracting("errorCode").isEqualTo(FolderErrorCode.MERGE_INTO_SELF)
            assertThat(folderService.list(fixture.galleryId, userId).single().details.single().photoIds)
                .containsExactlyElementsOf(photoIds)
        }

        @Test
        fun `다른 갤러리의 폴더나 경로의 컨셉 밖 폴더는 찾을 수 없다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val other = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 1)
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val otherConcept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("피로연"))
            val source = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장"))
            val target = folderService.createDetail(fixture.galleryId, otherConcept.id, userId, CreateDetailFolderRequest("행진"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(photoIds, source.id))
            val otherGalleryConcept = folderService.createConcept(
                other.galleryId, other.photographer.requiredId, CreateConceptFolderRequest("남의 컨셉"),
            )
            val otherGalleryDetail = folderService.createDetail(
                other.galleryId, otherGalleryConcept.id, other.photographer.requiredId, CreateDetailFolderRequest("남의 폴더"),
            )

            // when & then
            assertThatThrownBy {
                folderService.mergeDetail(fixture.galleryId, concept.id, source.id, userId, MergeDetailFolderRequest(otherGalleryDetail.id))
            }.isInstanceOf(FolderException::class.java).extracting("errorCode").isEqualTo(FolderErrorCode.DETAIL_NOT_FOUND)
            assertThatThrownBy {
                folderService.mergeDetail(fixture.galleryId, otherConcept.id, source.id, userId, MergeDetailFolderRequest(target.id))
            }.isInstanceOf(FolderException::class.java).extracting("errorCode").isEqualTo(FolderErrorCode.DETAIL_NOT_FOUND)
            assertThat(assignmentRepository.findAllByDetailFolderId(source.id).map { it.photoId }).containsExactlyElementsOf(photoIds)
        }
    }

    @Nested
    @DisplayName("합치기를 되돌릴 때")
    inner class UndoMerge {

        @Test
        fun `원본 폴더가 원래 id와 순서로 돌아오고 사진이 옮기기 전 배정 그대로 돌아간다`() {
            // given — 부부가 사진을 "입장 1"에 넣고, 작가가 "입장 1"을 "입장 3"에 합친 뒤 새 폴더 "행진"을 만들었다
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val memberId = fixture.member.requiredId
            val photographerId = fixture.photographer.requiredId
            val (sourcePhotos, targetPhotos) = photoFixture.업로드된_사진(fixture.galleryId, 3).let { it.take(2) to it.drop(2) }
            val concept = folderService.createConcept(fixture.galleryId, memberId, CreateConceptFolderRequest("본식"))
            val (first, source, target) = listOf("입장 1", "입장 2", "입장 3").map { name ->
                folderService.createDetail(fixture.galleryId, concept.id, memberId, CreateDetailFolderRequest(name))
            }
            folderService.movePhotos(fixture.galleryId, memberId, MoveFolderPhotosRequest(sourcePhotos, source.id))
            folderService.movePhotos(fixture.galleryId, memberId, MoveFolderPhotosRequest(targetPhotos, target.id))
            val before = assignmentRepository.findAllByPhotoIdIn(sourcePhotos).associate { it.photoId to it.assignedAt.toInstant() }
            val merged = folderService.mergeDetail(
                fixture.galleryId, concept.id, source.id, photographerId, MergeDetailFolderRequest(target.id),
            )
            val added = folderService.createDetail(fixture.galleryId, concept.id, memberId, CreateDetailFolderRequest("행진"))

            // when
            val undone = folderService.undoMerge(fixture.galleryId, merged.mergeId, photographerId)

            // then
            val details = folderService.list(fixture.galleryId, memberId).single().details
            val restored = assignmentRepository.findAllByPhotoIdIn(sourcePhotos)
            assertSoftly { softly ->
                softly.assertThat(undone.source.id).isEqualTo(source.id)
                softly.assertThat(undone.source.photoIds).containsExactlyInAnyOrderElementsOf(sourcePhotos)
                softly.assertThat(undone.target.photoIds).containsExactlyElementsOf(targetPhotos)
                softly.assertThat(details.map { it.id }).containsExactly(first.id, source.id, target.id, added.id)
                softly.assertThat(details[1].sortOrder).isEqualTo(source.sortOrder)
                softly.assertThat(details[1].createdSource).isEqualTo(FolderSource.USER)
                softly.assertThat(details[1].photoIds).containsExactlyInAnyOrderElementsOf(sourcePhotos)
                softly.assertThat(restored.map { it.assignedByUserId }).containsOnly(memberId)
                softly.assertThat(restored.associate { it.photoId to it.assignedAt.toInstant() }).isEqualTo(before)
            }
        }

        @Test
        fun `한 번만 되돌릴 수 있다`() {
            // given
            val (galleryId, userId, mergeId) = 합친_폴더()
            folderService.undoMerge(galleryId, mergeId, userId)

            // when & then
            assertThatThrownBy { folderService.undoMerge(galleryId, mergeId, userId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.MERGE_ALREADY_UNDONE)
        }

        @Test
        fun `합친 지 3분이 지나면 되돌릴 수 없다`() {
            // given
            val (galleryId, userId, mergeId) = 합친_폴더()
            jdbcTemplate.update("UPDATE detail_folder_merges SET merged_at = merged_at - interval '4 minutes' WHERE id = ?", mergeId)

            // when & then
            assertThatThrownBy { folderService.undoMerge(galleryId, mergeId, userId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.MERGE_UNDO_EXPIRED)
        }

        @Test
        fun `합친 뒤 사진을 다시 옮겼으면 거절하고 아무것도 바꾸지 않는다`() {
            // given — 합친 뒤 옮겨 온 사진 한 장을 미분류로 뺐다
            val (galleryId, userId, mergeId, movedPhotoIds) = 합친_폴더()
            val movedPhoto = movedPhotoIds.first()
            folderService.movePhotos(galleryId, userId, MoveFolderPhotosRequest(listOf(movedPhoto), null))
            val before = folderService.list(galleryId, userId)

            // when & then
            assertThatThrownBy { folderService.undoMerge(galleryId, mergeId, userId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.MERGE_UNDO_CONFLICT)
            assertThat(folderService.list(galleryId, userId)).isEqualTo(before)
        }

        @Test
        fun `다른 갤러리의 합치기는 찾을 수 없고 대상 폴더가 지워졌으면 거절한다`() {
            // given
            val (galleryId, userId, mergeId) = 합친_폴더()
            val other = galleryFixture.멤버와_열린_갤러리()
            val targetId = folderService.list(galleryId, userId).single().details.single().id
            val conceptId = folderService.list(galleryId, userId).single().id

            // when & then — 다른 갤러리 경로로는 찾을 수 없다
            assertThatThrownBy { folderService.undoMerge(other.galleryId, mergeId, other.photographer.requiredId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.MERGE_NOT_FOUND)

            // when & then — 대상 폴더를 지워도 기록은 남지만(정리 작업이 숨은 원본을 찾아야 한다) 되돌릴 곳이 없다
            folderService.deleteDetail(galleryId, conceptId, targetId, userId)
            assertThatThrownBy { folderService.undoMerge(galleryId, mergeId, userId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.MERGE_UNDO_CONFLICT)
        }

        /** 한 컨셉 안에서 사진 2장이 든 "입장 1"을 "입장 2"(사진 1장)에 합친 갤러리. */
        private fun 합친_폴더(): MergedGallery {
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val (sourcePhotos, targetPhotos) = photoFixture.업로드된_사진(fixture.galleryId, 3).let { it.take(2) to it.drop(2) }
            val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
            val source = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장 1"))
            val target = folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장 2"))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(sourcePhotos, source.id))
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(targetPhotos, target.id))
            val merged = folderService.mergeDetail(fixture.galleryId, concept.id, source.id, userId, MergeDetailFolderRequest(target.id))
            return MergedGallery(fixture.galleryId, userId, merged.mergeId, sourcePhotos)
        }
    }
}

private data class MergedGallery(val galleryId: Long, val userId: Long, val mergeId: Long, val movedPhotoIds: List<Long>)
