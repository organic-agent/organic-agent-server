package com.soma.wes.folder.support

import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.dto.request.CreateConceptFolderRequest
import com.soma.wes.folder.dto.request.MoveFolderPhotosRequest
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.service.FolderService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

// [REFACTOR-RENAME 2026-09-27] AiFolderService → AiFolderMaterializeService (클래스 이름만 변경, 동작 동일). 테스트 파일도 AiFolderServiceTest에서 이름 변경
// [REFACTOR-SUPPORT 2026-09-27] folder/service/AiFolderMaterializeServiceTest → folder/support/AiFolderMaterializerTest (프로덕션 패키지 미러링)
@IntegrationTest
@DisplayName("AI 폴더 세트를 실체화할 때")
class AiFolderMaterializerTest @Autowired constructor(
    private val materializer: AiFolderMaterializer,
    private val folderService: FolderService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val assignmentRepository: DetailFolderAssignmentRepository,
) {

    /**
     * 배경: 해변 3장(그룹 1) + 정원 2장(그룹 2) + 분석 행은 있지만 그룹이 없는 1장(→ "기타").
     * 새 세트를 만드는 응답은 DB를 다시 읽지 않고 메모리에서 조립하므로, 같은 세트를 두 번째 요청했을 때
     * DB에서 읽어 만든 응답과 같아야 한다. 작가 버튼([FolderService.createFromAnalysis])도 같은 세트를 돌려준다.
     */
    @Test
    fun `새로 만든 응답은 DB에서 다시 읽은 응답과 같고 배정 행이 배치로 적재된다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val beach = analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
        val garden = analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
        // 분류는 끝났지만 그룹에 이름이 붙지 않은 사진은 "기타"로 간다. 분류가 아직 안 끝난 사진은 폴더에 넣지 않는다 —
        // 잡이 categorize 를 보낸 뒤에 올라온 사진이고, 다음 잡이 제자리에 넣는다.
        val unnamed = analyzed(fixture.galleryId, count = 1, embedGroupId = 9)
        val uncategorized = photoFixture.임베딩된_사진(fixture.galleryId, 1).single()
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 2, conceptName = "야외 정원·건물", detailName = "정원")

        // when
        val created = materializer.materialize(fixture.galleryId)
        val reread = materializer.materialize(fixture.galleryId)
        val fromButton = folderService.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)

        // then
        val assignments = assignmentRepository.findAllPhotoIdsByGalleryId(fixture.galleryId)
        assertSoftly { softly ->
            softly.assertThat(reread).isEqualTo(created)
            softly.assertThat(fromButton).isEqualTo(created)
            softly.assertThat(created.map { it.name }).containsExactly("야외 자연", "야외 정원·건물", "기타")
            softly.assertThat(created.flatMap { c -> c.details.map { it.photoIds } })
                .containsExactly(beach, garden, unnamed)
            softly.assertThat(assignments).containsExactlyInAnyOrderElementsOf(beach + garden + unnamed)
            softly.assertThat(assignments).doesNotContain(uncategorized)
            softly.assertThat(assignmentRepository.findAllByPhotoIdIn(beach).map { it.assignedSource })
                .containsOnly(FolderSource.AI)
        }
        assertThat(created.flatMap { it.details }.map { it.galleryId }).containsOnly(fixture.galleryId)
    }

    /** 폴더를 지워도 남은 폴더의 sortOrder는 당겨지지 않는다 — 개수로 정렬 순서를 정하면 새 세트가 기존 폴더 사이에 끼었다. */
    @Test
    fun `앞쪽 폴더를 지운 뒤에도 새 세트는 기존 폴더 맨 뒤에 붙는다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val userId = fixture.photographer.requiredId
        val (first, second, third, fourth) = listOf("A", "B", "C", "D").map { name ->
            folderService.createConcept(fixture.galleryId, userId, CreateConceptFolderRequest(name))
        }
        folderService.deleteConcept(fixture.galleryId, first.id, userId)
        folderService.deleteConcept(fixture.galleryId, second.id, userId)
        analyzed(fixture.galleryId, count = 2, embedGroupId = 1)
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")

        // when
        materializer.materialize(fixture.galleryId)

        // then
        assertThat(folderService.list(fixture.galleryId, userId).map { it.name })
            .containsExactly(third.name, fourth.name, "야외 자연")
    }

    /**
     * 사진을 나눠 올린 경우 — 먼저 올린 사진으로 폴더가 만들어진 뒤 사진이 더 올라와 다시 분석했다.
     * AI 가 붙이는 이름은 실행마다 달라지므로, 새 잡의 배정에는 일부러 다른 이름을 준다.
     */
    @Nested
    @DisplayName("나눠 올린 사진을 합칠 때")
    inner class Merge {

        @Test
        fun `새 사진은 같은 그룹의 옛 사진이 든 폴더에 들어가고 폴더는 늘지 않는다`() {
            // given — 해변(그룹 1) 3장, 정원(그룹 2) 2장으로 폴더가 만들어졌다
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val beach = analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            val garden = analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)
            val before = folderService.list(fixture.galleryId, userId)

            // when — 해변 2장, 정원 1장이 더 올라왔고 새 잡은 같은 그룹을 다른 이름으로 불렀다
            val moreBeach = analyzed(fixture.galleryId, count = 2, embedGroupId = 1)
            val moreGarden = analyzed(fixture.galleryId, count = 1, embedGroupId = 2)
            val secondJob = recommendationFixture.분석_잡(fixture.galleryId)
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 1, conceptName = "바닷가", detailName = "모래사장")
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 2, conceptName = "정원", detailName = "꽃밭")
            val added = materializer.materialize(fixture.galleryId)

            // then — 전에는 "바닷가"·"정원" 폴더가 뒤에 따로 붙었다
            val after = folderService.list(fixture.galleryId, userId)
            assertSoftly { softly ->
                softly.assertThat(after.map { it.name }).containsExactly("야외 자연", "야외 정원·건물")
                softly.assertThat(after.map { concept -> concept.details.map { it.name } }).isEqualTo(before.map { concept -> concept.details.map { it.name } })
                softly.assertThat(after[0].details.single().photoIds).containsExactlyInAnyOrderElementsOf(beach + moreBeach)
                softly.assertThat(after[1].details.single().photoIds).containsExactlyInAnyOrderElementsOf(garden + moreGarden)
                // 응답은 이번에 넣은 사진만 싣는다
                softly.assertThat(added.flatMap { concept -> concept.details.flatMap { it.photoIds } })
                    .containsExactlyInAnyOrderElementsOf(moreBeach + moreGarden)
            }
        }

        @Test
        fun `사용자가 옮겨 둔 폴더를 새 사진이 따라간다`() {
            // given — 사용자가 해변 사진을 전부 정원 폴더로 옮겼다
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            val beach = analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)
            val gardenDetailId = folderService.list(fixture.galleryId, userId)[1].details.single().id
            folderService.movePhotos(fixture.galleryId, userId, MoveFolderPhotosRequest(photoIds = beach, targetDetailFolderId = gardenDetailId))

            // when
            val moreBeach = analyzed(fixture.galleryId, count = 1, embedGroupId = 1).single()
            secondJobWithSameNames(fixture.galleryId)
            materializer.materialize(fixture.galleryId)

            // then
            val gardenDetail = folderService.list(fixture.galleryId, userId)[1].details.single()
            assertThat(gardenDetail.photoIds).contains(moreBeach)
        }

        @Test
        fun `기존 폴더에 맞지 않는 사진은 최소 장수가 모일 때까지 미분류로 남는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)

            // when — 새 장면(그룹 3) 3장만 올라왔다. 최소 5장에 못 미친다
            val few = analyzed(fixture.galleryId, count = 3, embedGroupId = 3)
            val secondJob = secondJobWithSameNames(fixture.galleryId)
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 3, conceptName = "한복", detailName = "마당")

            // then — 넣을 곳이 없으니 "새로 넣을 사진 없음"이고 폴더는 그대로다
            assertThatThrownBy { materializer.materialize(fixture.galleryId) }
                .isInstanceOf(FolderException::class.java)
                .extracting("errorCode")
                .isEqualTo(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)
            assertThat(assignmentRepository.findAllPhotoIdsByGalleryId(fixture.galleryId)).doesNotContainAnyElementsOf(few)

            // 2장이 더 올라와 5장이 되면 새 폴더로 묶인다
            val more = analyzed(fixture.galleryId, count = 2, embedGroupId = 3)
            val thirdJob = secondJobWithSameNames(fixture.galleryId)
            recommendationFixture.컨셉_배정(thirdJob, fixture.galleryId, embedGroupId = 3, conceptName = "한복", detailName = "마당")
            materializer.materialize(fixture.galleryId)

            val folders = folderService.list(fixture.galleryId, userId)
            assertSoftly { softly ->
                softly.assertThat(folders.map { it.name }).containsExactly("야외 자연", "야외 정원·건물", "한복")
                softly.assertThat(folders.last().details.single().photoIds).containsExactlyInAnyOrderElementsOf(few + more)
            }
        }

        @Test
        fun `옛 사진이 없는 그룹은 같은 컨셉의 옛 사진이 든 컨셉 폴더 아래에 새 세부 폴더로 붙는다`() {
            // given
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)

            // when — 새 그룹 3(5장)을 새 잡이 해변과 같은 컨셉으로 묶었다. 컨셉 이름은 전과 다르다
            val rocks = analyzed(fixture.galleryId, count = 5, embedGroupId = 3)
            val secondJob = recommendationFixture.분석_잡(fixture.galleryId)
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 1, conceptName = "바닷가", detailName = "모래사장")
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 2, conceptName = "정원", detailName = "꽃밭")
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 3, conceptName = "바닷가", detailName = "갯바위")
            materializer.materialize(fixture.galleryId)

            // then — "야외 자연" 아래, 기존 세부 폴더 뒤에 붙는다
            val folders = folderService.list(fixture.galleryId, userId)
            assertSoftly { softly ->
                softly.assertThat(folders.map { it.name }).containsExactly("야외 자연", "야외 정원·건물")
                softly.assertThat(folders[0].details.map { it.name }).containsExactly("해변", "갯바위")
                softly.assertThat(folders[0].details.last().photoIds).containsExactlyInAnyOrderElementsOf(rocks)
            }
        }

        @Test
        fun `합치기만 한 잡을 다시 물질화하면 에러 없이 같은 결과를 돌려준다`() {
            // given — 두 번째 잡은 기존 폴더에 합치기만 했다(새 컨셉 폴더 없음)
            val fixture = galleryFixture.멤버와_열린_갤러리()
            analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)
            val moreBeach = analyzed(fixture.galleryId, count = 2, embedGroupId = 1)
            secondJobWithSameNames(fixture.galleryId)
            val first = materializer.materialize(fixture.galleryId)
            val assignedAfterFirst = assignmentRepository.findAllPhotoIdsByGalleryId(fixture.galleryId)

            // when
            val second = materializer.materialize(fixture.galleryId)

            // then — 배정은 그대로이고 응답도 첫 호출과 같다
            assertSoftly { softly ->
                softly.assertThat(second).isEqualTo(first)
                softly.assertThat(second.flatMap { concept -> concept.details.flatMap { it.photoIds } })
                    .containsExactlyInAnyOrderElementsOf(moreBeach)
                softly.assertThat(assignmentRepository.findAllPhotoIdsByGalleryId(fixture.galleryId))
                    .containsExactlyInAnyOrderElementsOf(assignedAfterFirst)
            }
        }

        @Test
        fun `새 세부 폴더만 만든 잡을 다시 물질화해도 폴더가 또 생기지 않는다`() {
            // given — 두 번째 잡은 기존 컨셉 폴더 아래에 새 세부 폴더를 만들었다
            val fixture = galleryFixture.멤버와_열린_갤러리()
            val userId = fixture.photographer.requiredId
            analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
            analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
            firstJob(fixture.galleryId)
            materializer.materialize(fixture.galleryId)
            analyzed(fixture.galleryId, count = 5, embedGroupId = 3)
            val secondJob = recommendationFixture.분석_잡(fixture.galleryId)
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 1, conceptName = "바닷가", detailName = "모래사장")
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 2, conceptName = "정원", detailName = "꽃밭")
            recommendationFixture.컨셉_배정(secondJob, fixture.galleryId, embedGroupId = 3, conceptName = "바닷가", detailName = "갯바위")
            val first = materializer.materialize(fixture.galleryId)

            // when
            val second = materializer.materialize(fixture.galleryId)

            // then
            assertSoftly { softly ->
                softly.assertThat(second).isEqualTo(first)
                softly.assertThat(folderService.list(fixture.galleryId, userId)[0].details.map { it.name }).containsExactly("해변", "갯바위")
            }
        }

        private fun firstJob(galleryId: Long): Long {
            val jobId = recommendationFixture.분석_잡(galleryId)
            recommendationFixture.컨셉_배정(jobId, galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
            recommendationFixture.컨셉_배정(jobId, galleryId, embedGroupId = 2, conceptName = "야외 정원·건물", detailName = "정원")
            return jobId
        }

        private fun secondJobWithSameNames(galleryId: Long): Long = firstJob(galleryId)
    }

    private fun analyzed(galleryId: Long, count: Int, embedGroupId: Int): List<Long> =
        photoFixture.임베딩된_사진(galleryId, count).onEach { photoId ->
            recommendationFixture.분석_결과(photoId = photoId, embedGroupId = embedGroupId, burstId = embedGroupId)
        }
}
