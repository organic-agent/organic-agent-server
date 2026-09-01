package com.soma.wes.folder.service

import com.soma.wes.folder.domain.PhotoFolder
import com.soma.wes.folder.domain.PhotoFolderGroup
import com.soma.wes.folder.domain.PhotoFolderItem
import com.soma.wes.folder.dto.response.PhotoFolderGroupResponse
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.folder.repository.PhotoFolderItemRepository
import com.soma.wes.folder.repository.PhotoFolderRepository
import com.soma.wes.folder.support.AiFolderPlanner
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.support.AiConceptAssignmentLoader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * AI 분석의 컨셉 배정으로 폴더 세트를 만드는 유스케이스.
 *
 * [PhotoFolderGroupService]에서 분리한 이유는 관리자 런타임의 경계다 — 그 서비스는
 * `AdminDomainDependenciesConfiguration`이 명시적으로 import하는데, AI 폴더 생성은 관리자가
 * 재사용하지 않는 기능이라 recommendation 의존을 그쪽 컨텍스트에 끌고 들어가면 안 된다.
 */
@Service
class AiFolderGroupService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
    private val photoFolderRepository: PhotoFolderRepository,
    private val photoFolderItemRepository: PhotoFolderItemRepository,
    private val folderViewAssembler: FolderViewAssembler,
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val aiConceptAssignmentLoader: AiConceptAssignmentLoader,
    private val aiFolderPlanner: AiFolderPlanner,
) {

    /**
     * 최신 컨셉 배정으로 "큰 분류(부모) → 컨셉(자식)" 세트를 만든다. 부모 여러 개가 한 번에
     * 생기고, 같은 `analysisJobId`가 세트 키다.
     *
     * 이미 AI 폴더가 있어도 지우거나 덮어쓰지 않고 **새 세트를 하나 더** 만든다 — 사용자가 편집한
     * 폴더를 서버가 지우는 일은 없고, 옛 세트는 사용자가 지운다. 작가 전용이다(부부는 만들어진
     * 폴더를 편집만 한다).
     */
    @Transactional
    fun createFromAnalysis(galleryId: Long, userId: Long): List<PhotoFolderGroupResponse> {
        galleryAccessPolicy.requirePhotographer(galleryId, userId)

        val latest = aiConceptAssignmentLoader.loadLatest(galleryId)
            ?: throw FolderException(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
        val members = loadMembers(galleryId)
        if (members.isEmpty()) {
            throw FolderException(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)
        }

        val assignments = latest.assignments.map {
            AiFolderPlanner.GroupAssignment(
                embedGroupId = it.embedGroupId,
                parentName = it.parentName,
                conceptName = it.conceptName,
                needsReview = it.needsReview,
            )
        }
        val plans = aiFolderPlanner.plan(assignments, members)

        val groups = plans.map { plan ->
            val group = photoFolderGroupRepository.save(
                PhotoFolderGroup.aiOf(galleryId, plan.parentName, latest.jobId),
            )
            plan.folders.forEach { folderPlan ->
                val folder = photoFolderRepository.save(
                    PhotoFolder.aiOf(group, folderPlan.name, folderPlan.category, folderPlan.needsReview),
                )
                photoFolderItemRepository.saveAll(
                    folderPlan.photoIds.mapIndexed { sortOrder, photoId ->
                        PhotoFolderItem(
                            groupId = group.requiredId,
                            folderId = folder.requiredId,
                            photoId = photoId,
                            sortOrder = sortOrder,
                        )
                    },
                )
            }
            group
        }

        return responsesOf(groups)
    }

    /**
     * 분석 행이 있는 사진만 노출 순서대로. 폴더 생성 뒤에 올라온(아직 분석 안 된) 사진은 어떤
     * 폴더에도 없다 — 다음 분석·생성에서 들어온다.
     */
    private fun loadMembers(galleryId: Long): List<AiFolderPlanner.MemberPhoto> {
        val analysisByPhotoId = photoAnalysisRepository.findAllByGalleryId(galleryId)
            .associateBy { it.photoId }

        return photoRepository.findAllByGalleryIdOrderByDisplayOrderAsc(galleryId)
            .sortedWith(Photo.DISPLAY_ORDER)
            .mapNotNull { photo ->
                analysisByPhotoId[photo.requiredId]?.let { analysis ->
                    AiFolderPlanner.MemberPhoto(
                        photoId = photo.requiredId,
                        embedGroupId = analysis.embedGroupId,
                        subjects = analysis.subjects,
                        clusterId = analysis.clusterId,
                    )
                }
            }
    }

    /** [createFromAnalysis]가 만든 세트 전체의 응답. 폴더 수와 무관하게 요약 질의는 한 번이다. */
    private fun responsesOf(groups: List<PhotoFolderGroup>): List<PhotoFolderGroupResponse> {
        val folders = photoFolderRepository.findAllByGroupIdInOrderByIdAsc(groups.map { it.requiredId })
        val summaries = folderViewAssembler.summariesByFolderId(folders)
        val foldersByGroupId = folders.groupBy { it.groupId }

        return groups.map { group ->
            PhotoFolderGroupResponse.of(
                group = group,
                folders = foldersByGroupId[group.requiredId].orEmpty()
                    .mapNotNull { summaries[it.requiredId] },
            )
        }
    }
}
