package com.soma.wes.folder.support

import com.soma.wes.analysis.dto.ConceptAssignmentDto
import com.soma.wes.folder.dto.FolderPlanDto
import com.soma.wes.folder.dto.PhotoAnalysisGroupingDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 새 사진의 자리를 정하는 규칙 — 같은 그룹의 옛 사진이 든 폴더 → 같은 컨셉의 옛 사진이 든 컨셉 폴더 → 같은 이름의 컨셉 폴더 → 새 폴더.
 * 사진 id 1~99 는 옛 사진, 100 이상은 새 사진으로 쓴다. 세부 폴더 10·11 은 컨셉 폴더 1 아래, 20 은 컨셉 폴더 2 아래다.
 */
class AiFolderPlannerUnitTest {

    private val planner = AiFolderPlanner()

    private val conceptFolderIdByDetailFolderId = mapOf(10L to 1L, 11L to 1L, 20L to 2L)
    private val conceptFolderIdByName = mapOf("야외 공원" to 1L, "실내" to 2L)

    private fun photo(photoId: Long, embedGroupId: Int?) =
        PhotoAnalysisGroupingDto(photoId = photoId, embedGroupId = embedGroupId, subjects = null, burstId = null)

    private fun assignment(embedGroupId: Int, conceptName: String, detailName: String, needsReview: Boolean = false) =
        ConceptAssignmentDto(embedGroupId = embedGroupId, conceptName = conceptName, detailName = detailName, needsReview = needsReview)

    private fun plan(
        assignments: List<ConceptAssignmentDto>,
        photos: List<PhotoAnalysisGroupingDto>,
        placed: Map<Long, Long>,
        minNewDetailPhotos: Int = 5,
    ): FolderPlanDto = planner.plan(
        assignments = assignments,
        photos = photos,
        detailFolderIdByPhotoId = placed,
        conceptFolderIdByDetailFolderId = conceptFolderIdByDetailFolderId,
        conceptFolderIdByName = conceptFolderIdByName,
        minNewDetailPhotos = minNewDetailPhotos,
    )

    @Nested
    @DisplayName("기존 세부 폴더에 합칠 때")
    inner class MergeIntoDetail {

        @Test
        fun `같은 그룹의 옛 사진 과반이 든 세부 폴더에 넣는다 — 이름이 달라져도`() {
            // given — 그룹 1의 옛 사진 3장 중 2장이 세부 폴더 10 에 있다. 이번 분석은 그 그룹을 다른 이름으로 불렀다
            val photos = listOf(photo(1, 1), photo(2, 1), photo(3, 1), photo(100, 1), photo(101, 1))
            val placed = mapOf(1L to 10L, 2L to 10L, 3L to 11L)

            // when
            val plan = plan(listOf(assignment(1, "야외 정원", "분수대")), photos, placed)

            // then — 새 폴더를 만들지 않는다. 최소 장수도 합치기에는 걸리지 않는다
            assertSoftly { softly ->
                softly.assertThat(plan.merges).isEqualTo(mapOf(10L to listOf(100L, 101L)))
                softly.assertThat(plan.newDetails).isEmpty()
                softly.assertThat(plan.newConcepts).isEmpty()
                softly.assertThat(plan.leftUnclassified).isEmpty()
            }
        }

        @Test
        fun `사용자가 옮긴 사진을 새 사진이 따라간다`() {
            // given — AI 는 그룹 1을 세부 폴더 10 에 넣었지만 사용자가 전부 20 으로 옮겼다
            val photos = listOf(photo(1, 1), photo(2, 1), photo(100, 1))
            val placed = mapOf(1L to 20L, 2L to 20L)

            // when
            val plan = plan(listOf(assignment(1, "야외 공원", "산책로")), photos, placed)

            // then
            assertThat(plan.merges).isEqualTo(mapOf(20L to listOf(100L)))
        }

        @Test
        fun `옛 사진이 흩어져 과반이 없으면 세부 폴더에 합치지 않는다`() {
            // given — 그룹 1의 옛 사진이 10 과 11 에 반반
            val photos = listOf(photo(1, 1), photo(2, 1)) + (100L..104L).map { photo(it, 1) }
            val placed = mapOf(1L to 10L, 2L to 11L)

            // when
            val plan = plan(listOf(assignment(1, "야외 공원", "산책로")), photos, placed)

            // then — 두 세부 폴더가 같은 컨셉 폴더(1) 아래라 그 아래에 새 세부 폴더를 만든다
            assertSoftly { softly ->
                softly.assertThat(plan.merges).isEmpty()
                softly.assertThat(plan.newDetails.keys).containsExactly(1L)
                softly.assertThat(plan.newDetails.getValue(1L).single().photoIds).containsExactly(100L, 101L, 102L, 103L, 104L)
            }
        }
    }

    @Nested
    @DisplayName("새 세부 폴더를 만들 때")
    inner class NewDetail {

        @Test
        fun `그룹에 옛 사진이 없으면 같은 컨셉의 옛 사진 과반이 든 컨셉 폴더 아래에 만든다`() {
            // given — 그룹 1(옛 사진)과 그룹 2(새 사진뿐)가 같은 컨셉이다. 그 컨셉의 옛 사진은 컨셉 폴더 2 아래(세부 20)에 있다
            val photos = listOf(photo(1, 1), photo(2, 1)) + (100L..104L).map { photo(it, 2) }
            val placed = mapOf(1L to 20L, 2L to 20L)
            val assignments = listOf(assignment(1, "야외 정원", "분수대"), assignment(2, "야외 정원", "장미 덩굴"))

            // when
            val plan = plan(assignments, photos, placed)

            // then — 이름("야외 정원")이 기존 폴더 이름과 달라도 옛 사진이 있는 컨셉 폴더로 간다
            val detail = plan.newDetails.getValue(2L).single()
            assertSoftly { softly ->
                softly.assertThat(detail.name).isEqualTo("장미 덩굴")
                softly.assertThat(detail.photoIds).hasSize(5)
                softly.assertThat(plan.newConcepts).isEmpty()
            }
        }

        @Test
        fun `옛 사진이 없는 컨셉은 같은 이름의 컨셉 폴더 아래에 만든다`() {
            // given — "실내" 컨셉의 사진은 전부 새 사진이다. 같은 이름의 컨셉 폴더(2)가 이미 있다
            val photos = listOf(photo(1, 9)) + (100L..104L).map { photo(it, 2) }
            val placed = mapOf(1L to 10L)

            // when
            val plan = plan(listOf(assignment(9, "야외 공원", "산책로"), assignment(2, "실내", "거울 앞")), photos, placed)

            // then
            assertThat(plan.newDetails.getValue(2L).single().name).isEqualTo("거울 앞")
        }

        @Test
        fun `어디에도 맞지 않으면 새 컨셉 폴더를 만든다`() {
            // given
            val photos = listOf(photo(1, 9)) + (100L..104L).map { photo(it, 2) }
            val placed = mapOf(1L to 10L)

            // when
            val plan = plan(listOf(assignment(9, "야외 공원", "산책로"), assignment(2, "한복", "마당")), photos, placed)

            // then
            val concept = plan.newConcepts.single()
            assertSoftly { softly ->
                softly.assertThat(concept.name).isEqualTo("한복")
                softly.assertThat(concept.details.single().name).isEqualTo("마당")
                softly.assertThat(plan.newDetails).isEmpty()
            }
        }

        @Test
        fun `최소 장수에 못 미치면 만들지 않고 미분류로 남긴다`() {
            // given — 기존 폴더에 맞지 않는 새 사진 4장(최소 5장)
            val photos = listOf(photo(1, 9)) + (100L..103L).map { photo(it, 2) }
            val placed = mapOf(1L to 10L)

            // when
            val plan = plan(listOf(assignment(9, "야외 공원", "산책로"), assignment(2, "한복", "마당")), photos, placed)

            // then
            assertSoftly { softly ->
                softly.assertThat(plan.isEmpty).isTrue()
                softly.assertThat(plan.leftUnclassified).containsExactlyInAnyOrder(100L, 101L, 102L, 103L)
            }
        }

        @Test
        fun `같은 세부 이름으로 가는 그룹들은 한 폴더로 합쳐 장수를 센다`() {
            // given — 그룹 2(3장)와 그룹 3(2장)이 같은 컨셉·세부 이름이다
            val photos = listOf(photo(1, 9)) + (100L..102L).map { photo(it, 2) } + (103L..104L).map { photo(it, 3) }
            val placed = mapOf(1L to 10L)
            val assignments = listOf(assignment(9, "야외 공원", "산책로"), assignment(2, "한복", "마당"), assignment(3, "한복", "마당"))

            // when
            val plan = plan(assignments, photos, placed)

            // then
            assertThat(plan.newConcepts.single().details.single().photoIds).hasSize(5)
        }
    }

    @Nested
    @DisplayName("처음 분석할 때")
    inner class FirstAnalysis {

        @Test
        fun `폴더에 든 사진이 없으면 최소 장수 없이 전부 새 폴더로 만든다`() {
            // given — 1장짜리 그룹도 있다
            val photos = listOf(photo(100, 1), photo(101, 1), photo(102, 2), photo(103, null))
            val assignments = listOf(assignment(1, "야외 공원", "산책로"), assignment(2, "실내", "거울 앞", needsReview = true))

            // when — 기존 폴더가 없다
            val plan = planner.plan(assignments = assignments, photos = photos, minNewDetailPhotos = 5)

            // then — 합치기가 생기기 전과 같은 결과: 사진 많은 순, "기타"는 맨 뒤
            assertSoftly { softly ->
                softly.assertThat(plan.newConcepts.map { it.name }).containsExactly("야외 공원", "실내", AiFolderPlanner.ETC_NAME)
                softly.assertThat(plan.newConcepts.map { concept -> concept.details.flatMap { it.photoIds } })
                    .containsExactly(listOf(100L, 101L), listOf(102L), listOf(103L))
                softly.assertThat(plan.newConcepts[1].details.single().needsReview).isTrue()
                softly.assertThat(plan.merges).isEmpty()
                softly.assertThat(plan.leftUnclassified).isEmpty()
            }
        }

        @Test
        fun `새 사진이 없으면 빈 계획이다`() {
            // when
            val plan = plan(listOf(assignment(1, "야외 공원", "산책로")), listOf(photo(1, 1)), mapOf(1L to 10L))

            // then
            assertThat(plan.isEmpty).isTrue()
        }
    }
}
