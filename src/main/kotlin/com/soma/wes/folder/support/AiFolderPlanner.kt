package com.soma.wes.folder.support

import com.soma.wes.folder.domain.FolderCategory
import org.springframework.stereotype.Component

/**
 * 컨셉 배정과 갤러리 사진을 "큰 분류(부모) → 컨셉(자식) → 사진" 폴더 플랜으로 바꾼다.
 *
 * 순수 계산이다 — DB도 시각도 만지지 않아 배정·사진만 넣으면 같은 플랜이 나온다. 저장은
 * 호출자(PhotoFolderGroupService)의 일이다.
 *
 * 규칙:
 * - 같은 부모 아래 같은 이름의 컨셉은 하나로 합친다 (여러 임베딩 그룹이 같은 컨셉일 수 있다).
 * - 배정이 없는 그룹의 사진(임베딩 미배정 포함)은 '기타/기타'로 모은다 — 무결할 필요 없다,
 *   확신 낮은 자리는 배지로 표시하고 사용자가 고친다.
 * - 부모·자식 모두 큰 것부터 정렬한다. '기타'는 크기와 무관하게 마지막이다.
 * - 폴더 안 사진은 임베딩 그룹 → 연사 클러스터 → 노출 순서로 붙여, 같은 세트·같은 순간이
 *   나란히 오게 한다(다중선택 편의). 노출 순서는 입력 순서로 받는다 — [plan]의 members는
 *   호출자가 노출 순서로 정렬해 넘겨야 한다.
 */
@Component
class AiFolderPlanner {

    fun plan(assignments: List<GroupAssignment>, members: List<MemberPhoto>): List<GroupPlan> {
        if (members.isEmpty()) {
            return emptyList()
        }
        val assignmentByGroup = assignments.associateBy { it.embedGroupId }

        val byFolder = members.groupBy { member ->
            val assignment = member.embedGroupId?.let { assignmentByGroup[it] }
            FolderKey(
                parentName = assignment?.parentName ?: ETC_NAME,
                conceptName = assignment?.conceptName ?: ETC_NAME,
            )
        }

        return byFolder.entries
            .groupBy({ it.key.parentName }, { it })
            .map { (parentName, folders) -> planParent(parentName, folders, assignmentByGroup) }
            .sortedWith(
                compareBy<GroupPlan> { it.parentName == ETC_NAME }
                    .thenByDescending { plan -> plan.folders.sumOf { it.photoIds.size } },
            )
    }

    private fun planParent(
        parentName: String,
        folders: List<Map.Entry<FolderKey, List<MemberPhoto>>>,
        assignmentByGroup: Map<Int, GroupAssignment>,
    ): GroupPlan {
        val folderPlans = folders.map { (key, folderMembers) ->
            val sorted = folderMembers.sortedWith(MEMBER_ORDER)
            FolderPlan(
                name = key.conceptName,
                category = majorityCategory(sorted),
                needsReview = sorted.any { member ->
                    member.embedGroupId?.let { assignmentByGroup[it] }?.needsReview ?: false
                },
                photoIds = sorted.map { it.photoId },
            )
        }.sortedWith(
            compareBy<FolderPlan> { it.name == ETC_NAME }
                .thenByDescending { it.photoIds.size },
        )

        return GroupPlan(parentName = parentName, folders = folderPlans)
    }

    /** 피사체 과반. 절반 이하이거나 어휘 밖 값뿐이면 카테고리 없음(null)이다. */
    private fun majorityCategory(members: List<MemberPhoto>): FolderCategory? {
        val counts = members
            .mapNotNull { FolderCategory.fromSubjects(it.subjects) }
            .groupingBy { it }
            .eachCount()
        val (category, count) = counts.maxByOrNull { it.value } ?: return null

        return category.takeIf { count * 2 > members.size }
    }

    private data class FolderKey(
        val parentName: String,
        val conceptName: String,
    )

    /** 배정 한 건. recommendation 엔티티를 그대로 받지 않는 이유 — 플래너는 folder의 순수 계산기다. */
    data class GroupAssignment(
        val embedGroupId: Int,
        val parentName: String,
        val conceptName: String,
        val needsReview: Boolean,
    )

    /** 사진 한 장. [embedGroupId]가 null 또는 미배정(-1)이면 배정 없는 사진으로 취급한다. */
    data class MemberPhoto(
        val photoId: Long,
        val embedGroupId: Int?,
        val subjects: String?,
        val clusterId: Int?,
    )

    data class GroupPlan(
        val parentName: String,
        val folders: List<FolderPlan>,
    )

    data class FolderPlan(
        val name: String,
        val category: FolderCategory?,
        val needsReview: Boolean,
        val photoIds: List<Long>,
    )

    companion object {

        /** 배정이 없거나 배경이 불분명한 사진이 모이는 이름. AI repo의 taxonomy와 같은 값이어야 한다. */
        const val ETC_NAME = "기타"

        /**
         * 폴더 안 사진 정렬. 입력이 노출 순서이므로(stable sort) 같은 그룹·클러스터 안에서는
         * 노출 순서가 유지된다. 그룹·클러스터가 없는 사진은 뒤로 보낸다.
         */
        private val MEMBER_ORDER = compareBy<MemberPhoto>(
            { it.embedGroupId ?: Int.MAX_VALUE },
            { it.clusterId ?: Int.MAX_VALUE },
        )
    }
}
