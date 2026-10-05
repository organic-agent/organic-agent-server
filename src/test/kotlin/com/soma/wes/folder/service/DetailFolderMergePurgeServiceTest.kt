package com.soma.wes.folder.service

import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.CreateDetailFolderRequest
import com.soma.wes.folder.dto.request.MergeDetailFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.dto.response.DetailFolderResponse
import com.soma.wes.folder.repository.DetailFolderMergeRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/** 스케줄러가 부르는 것과 같은 [DetailFolderMergePurgeService.purgeExpired]를 직접 부른다. */
@IntegrationTest
@DisplayName("되돌릴 시간이 지난 합치기를 정리할 때")
class DetailFolderMergePurgeServiceTest @Autowired constructor(
    private val purgeService: DetailFolderMergePurgeService,
    private val folderService: FolderService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val mergeRepository: DetailFolderMergeRepository,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `시간이 지난 합치기의 숨은 원본과 기록을 지우고 시간 안의 합치기와 다른 숨은 폴더는 남긴다`() {
        // given — 4분 전에 합친 것, 방금 합친 것, 합치기와 무관하게 숨은 폴더(관리자 휴지통 대역)
        val board = 폴더_넷()
        val expired = folderService.mergeDetail(board.galleryId, board.conceptId, board.details[0].id, board.userId, MergeDetailFolderRequest(board.details[1].id))
        val fresh = folderService.mergeDetail(board.galleryId, board.conceptId, board.details[2].id, board.userId, MergeDetailFolderRequest(board.details[1].id))
        시간을_되돌린다(expired.mergeId, board.details[0].id)
        val trashed = board.details[3].id
        jdbcTemplate.update("UPDATE detail_folders SET deleted_at = now() - interval '1 day' WHERE id = ?", trashed)

        // when
        purgeService.purgeExpired()

        // then
        assertSoftly { softly ->
            softly.assertThat(폴더_행이_있다(board.details[0].id)).isFalse()
            softly.assertThat(mergeRepository.existsById(expired.mergeId)).isFalse()
            softly.assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM detail_folder_merge_photos WHERE merge_id = ?", Long::class.java, expired.mergeId)).isZero()
            softly.assertThat(폴더_행이_있다(board.details[2].id)).isTrue()
            softly.assertThat(mergeRepository.existsById(fresh.mergeId)).isTrue()
            softly.assertThat(폴더_행이_있다(trashed)).isTrue()
        }
        // 방금 합친 것은 여전히 되돌릴 수 있다
        assertThat(folderService.undoMerge(board.galleryId, fresh.mergeId, board.userId).source.id).isEqualTo(board.details[2].id)
    }

    @Test
    fun `되돌린 합치기는 폴더를 남기고 기록만 지운다`() {
        // given
        val board = 폴더_넷()
        val merged = folderService.mergeDetail(board.galleryId, board.conceptId, board.details[0].id, board.userId, MergeDetailFolderRequest(board.details[1].id))
        folderService.undoMerge(board.galleryId, merged.mergeId, board.userId)
        시간을_되돌린다(merged.mergeId, board.details[0].id)

        // when
        purgeService.purgeExpired()

        // then
        assertThat(mergeRepository.existsById(merged.mergeId)).isFalse()
        assertThat(folderService.list(board.galleryId, board.userId).single().details.map { it.id })
            .containsExactlyElementsOf(board.details.map { it.id })
    }

    @Test
    fun `대상 폴더가 지워진 합치기도 숨은 원본을 지운다`() {
        // given
        val board = 폴더_넷()
        val merged = folderService.mergeDetail(board.galleryId, board.conceptId, board.details[0].id, board.userId, MergeDetailFolderRequest(board.details[1].id))
        folderService.deleteDetail(board.galleryId, board.conceptId, board.details[1].id, board.userId)
        시간을_되돌린다(merged.mergeId, board.details[0].id)

        // when
        purgeService.purgeExpired()

        // then
        assertThat(폴더_행이_있다(board.details[0].id)).isFalse()
        assertThat(mergeRepository.existsById(merged.mergeId)).isFalse()
    }

    /** 한 컨셉 아래 세부 폴더 넷, 각 폴더에 사진 한 장. */
    private fun 폴더_넷(): FolderBoard {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val userId = fixture.photographer.requiredId
        val photoIds = photoFixture.업로드된_사진(fixture.galleryId, 4)
        val concept = folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest("본식"))
        val details = photoIds.mapIndexed { index, photoId ->
            folderService.createDetail(fixture.galleryId, concept.id, userId, CreateDetailFolderRequest("입장 $index")).also { detail ->
                folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(listOf(photoId), detail.id))
            }
        }
        return FolderBoard(fixture.galleryId, userId, concept.id, details)
    }

    /** 합친 시각을 되돌릴 시간(3분) 밖으로 민다. 숨긴 시각도 합친 시각과 같아야 정리 대상이므로 함께 민다. */
    private fun 시간을_되돌린다(mergeId: Long, sourceDetailId: Long) {
        jdbcTemplate.update("UPDATE detail_folder_merges SET merged_at = merged_at - interval '4 minutes' WHERE id = ?", mergeId)
        jdbcTemplate.update(
            "UPDATE detail_folders SET deleted_at = deleted_at - interval '4 minutes' WHERE id = ? AND deleted_at IS NOT NULL",
            sourceDetailId,
        )
    }

    private fun 폴더_행이_있다(detailId: Long): Boolean =
        jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM detail_folders WHERE id = ?)", Boolean::class.java, detailId) == true
}

private data class FolderBoard(
    val galleryId: Long,
    val userId: Long,
    val conceptId: Long,
    val details: List<DetailFolderResponse>,
)
