package com.soma.wes.folder.support

import com.soma.wes.folder.domain.FolderCategory
import com.soma.wes.folder.support.AiFolderPlanner.GroupAssignment
import com.soma.wes.folder.support.AiFolderPlanner.MemberPhoto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 플래너는 순수 계산이라 스프링 없이 검증한다. */
class AiFolderPlannerUnitTest {

    private val planner = AiFolderPlanner()

    @Nested
    @DisplayName("폴더를 짤 때")
    inner class Plan {

        @Test
        fun `배정대로 부모-컨셉 2층을 만든다`() {
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "실내 스튜디오", conceptName = "스튜디오 소파", needsReview = false),
                GroupAssignment(embedGroupId = 2, parentName = "야외 자연", conceptName = "해변", needsReview = false),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 1),
                member(photoId = 2L, embedGroupId = 1),
                member(photoId = 3L, embedGroupId = 2),
            )

            // when
            val plans = planner.plan(assignments, members)

            // then
            assertSoftly { softly ->
                softly.assertThat(plans).hasSize(2)
                softly.assertThat(plans[0].parentName).isEqualTo("실내 스튜디오")
                softly.assertThat(plans[0].folders.single().name).isEqualTo("스튜디오 소파")
                softly.assertThat(plans[0].folders.single().photoIds).containsExactly(1L, 2L)
                softly.assertThat(plans[1].parentName).isEqualTo("야외 자연")
                softly.assertThat(plans[1].folders.single().photoIds).containsExactly(3L)
            }
        }

        @Test
        fun `같은 부모의 같은 컨셉 이름은 하나로 합친다`() {
            // 여러 임베딩 그룹이 같은 컨셉일 수 있다 — 화면에는 컨셉 폴더 하나로 보여야 한다.
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "야외 자연", conceptName = "해변", needsReview = false),
                GroupAssignment(embedGroupId = 2, parentName = "야외 자연", conceptName = "해변", needsReview = true),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 1),
                member(photoId = 2L, embedGroupId = 2),
            )

            // when
            val plan = planner.plan(assignments, members).single()

            // then
            assertSoftly { softly ->
                softly.assertThat(plan.folders).hasSize(1)
                softly.assertThat(plan.folders.single().photoIds).containsExactly(1L, 2L)
                // 합쳐진 그룹 중 하나라도 확신이 낮았으면 폴더에 배지가 남아야 한다.
                softly.assertThat(plan.folders.single().needsReview).isTrue()
            }
        }

        @Test
        fun `배정 없는 사진은 기타-기타로 모은다`() {
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "야외 자연", conceptName = "해변", needsReview = false),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 1),
                member(photoId = 2L, embedGroupId = 99),
                member(photoId = 3L, embedGroupId = null),
            )

            // when
            val plans = planner.plan(assignments, members)

            // then
            val etc = plans.last()
            assertSoftly { softly ->
                softly.assertThat(etc.parentName).isEqualTo(AiFolderPlanner.ETC_NAME)
                softly.assertThat(etc.folders.single().name).isEqualTo(AiFolderPlanner.ETC_NAME)
                softly.assertThat(etc.folders.single().photoIds).containsExactlyInAnyOrder(2L, 3L)
            }
        }

        @Test
        fun `부모와 자식은 큰 것부터, 기타는 마지막이다`() {
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "실내 스튜디오", conceptName = "소파", needsReview = false),
                GroupAssignment(embedGroupId = 2, parentName = "실내 스튜디오", conceptName = "흑백", needsReview = false),
                GroupAssignment(embedGroupId = 3, parentName = "야외 자연", conceptName = "해변", needsReview = false),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 1),
                member(photoId = 2L, embedGroupId = 2),
                member(photoId = 3L, embedGroupId = 2),
                member(photoId = 4L, embedGroupId = 3),
                member(photoId = 5L, embedGroupId = 3),
                member(photoId = 6L, embedGroupId = 3),
                member(photoId = 7L, embedGroupId = 3),
                member(photoId = 8L, embedGroupId = null),
            )

            // when
            val plans = planner.plan(assignments, members)

            // then
            assertSoftly { softly ->
                softly.assertThat(plans.map { it.parentName })
                    .containsExactly("야외 자연", "실내 스튜디오", AiFolderPlanner.ETC_NAME)
                softly.assertThat(plans[1].folders.map { it.name }).containsExactly("흑백", "소파")
            }
        }

        @Test
        fun `폴더 안 사진은 임베딩 그룹, 연사 클러스터, 입력 순서로 붙인다`() {
            // 같은 세트·같은 순간이 나란히 와야 다중선택이 편하다.
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "야외 자연", conceptName = "해변", needsReview = false),
                GroupAssignment(embedGroupId = 2, parentName = "야외 자연", conceptName = "해변", needsReview = false),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 2, clusterId = 5),
                member(photoId = 2L, embedGroupId = 1, clusterId = 9),
                member(photoId = 3L, embedGroupId = 1, clusterId = 4),
                member(photoId = 4L, embedGroupId = 1, clusterId = 4),
            )

            // when
            val folder = planner.plan(assignments, members).single().folders.single()

            // then
            assertThat(folder.photoIds).containsExactly(3L, 4L, 2L, 1L)
        }

        @Test
        fun `피사체 과반이면 카테고리가 붙고 아니면 없다`() {
            // given
            val assignments = listOf(
                GroupAssignment(embedGroupId = 1, parentName = "야외 자연", conceptName = "해변", needsReview = false),
                GroupAssignment(embedGroupId = 2, parentName = "실내 스튜디오", conceptName = "소파", needsReview = false),
            )
            val members = listOf(
                member(photoId = 1L, embedGroupId = 1, subjects = "bride"),
                member(photoId = 2L, embedGroupId = 1, subjects = "bride"),
                member(photoId = 3L, embedGroupId = 1, subjects = "couple"),
                member(photoId = 4L, embedGroupId = 2, subjects = "bride"),
                member(photoId = 5L, embedGroupId = 2, subjects = "couple"),
            )

            // when
            val plans = planner.plan(assignments, members)

            // then
            val beach = plans.single { it.parentName == "야외 자연" }.folders.single()
            val sofa = plans.single { it.parentName == "실내 스튜디오" }.folders.single()
            assertSoftly { softly ->
                softly.assertThat(beach.category).isEqualTo(FolderCategory.BRIDE)
                softly.assertThat(sofa.category).isNull()
            }
        }

        @Test
        fun `사진이 없으면 빈 플랜이다`() {
            // when & then
            assertThat(planner.plan(emptyList(), emptyList())).isEmpty()
        }
    }

    private fun member(
        photoId: Long,
        embedGroupId: Int?,
        subjects: String? = null,
        clusterId: Int? = null,
    ) = MemberPhoto(photoId = photoId, embedGroupId = embedGroupId, subjects = subjects, clusterId = clusterId)
}
