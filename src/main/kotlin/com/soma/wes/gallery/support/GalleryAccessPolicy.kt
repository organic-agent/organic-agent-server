package com.soma.wes.gallery.support

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireById
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 갤러리에서 누가 무엇을 할 수 있는지.
 */
@Service
class GalleryAccessPolicy(
    private val galleryRepository: GalleryRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val studioRepository: StudioRepository,
    private val photoSelectionRepository: PhotoSelectionRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val clock: Clock,
) {

    /** 갤러리 상태 전이·계약 장수·업로드 URL 발급·임베딩 실행·초대 발급/폐기·제출 철회. */
    @Transactional(readOnly = true)
    fun requireManager(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isManager(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    /**
     * 초대 고객도 선택 중에는 분류를 정리한다. 제출과 같은 갤러리 잠금을 잡은 뒤 상태를
     * 확인해야 제출이 끝난 뒤 대기 중이던 이동이 저장되는 일을 막을 수 있다.
     * 작업공간 관리자는 제출 이후에도 기존 관리 권한을 유지한다.
     */
    @Transactional
    fun requireFolderEditor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        gallery.requireWritable(ZonedDateTime.now(clock))
        if (isManager(gallery, userId) || isPersonalParticipant(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        requireSelectable(gallery, personal = false)
        photoSelectionRepository.findByGalleryId(galleryId)?.requireEditable()
        return gallery
    }

    /** 개인 갤러리의 두 참여자는 업로드를 함께 하되, 계약 설정은 소유자만 바꾼다. */
    @Transactional(readOnly = true)
    fun requireUploader(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isManager(gallery, userId) && !isPersonalParticipant(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        gallery.requireWritable(ZonedDateTime.now(clock))
        return gallery
    }

    /**
     * 개인 갤러리 정보(이름·목표일·고를 장수) 수정. 소유자와 파트너 모두 연다.
     * 초대 갤러리는 작가가 계약으로 정하는 값이라 여기를 지나지 않는다.
     */
    @Transactional(readOnly = true)
    fun requirePersonalParticipant(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isPersonalParticipant(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    @Transactional(readOnly = true)
    fun requireRetouchProcessor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isManager(gallery, userId) && !isPersonalParticipant(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        gallery.requireWritable(ZonedDateTime.now(clock))
        return gallery
    }

    /** 선택 마감 후에도 보정 요청은 계속되지만 종료된 갤러리에는 추가할 수 없다. */
    @Transactional(readOnly = true)
    fun requireRetouchRequester(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        requireParticipant(gallery, userId)
        gallery.requireWritable(ZonedDateTime.now(clock))
        return gallery
    }

    @Transactional(readOnly = true)
    fun canInspectRetouchDrafts(galleryId: Long, userId: Long): Boolean {
        val gallery = galleryRepository.requireById(galleryId)
        return isManager(gallery, userId) || isPersonalParticipant(gallery, userId)
    }

    @Transactional(readOnly = true)
    fun isStudioManager(galleryId: Long, userId: Long): Boolean {
        val gallery = galleryRepository.requireById(galleryId)
        return workspaceRepository.findById(gallery.workspaceId).orElse(null)?.type == WorkspaceType.STUDIO &&
            workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, userId) != null
    }

    @Transactional(readOnly = true)
    fun isPersonalGallery(galleryId: Long): Boolean {
        val gallery = galleryRepository.requireById(galleryId)
        return workspaceRepository.findById(gallery.workspaceId).orElse(null)?.type == WorkspaceType.PERSONAL
    }

    /** 대기 화면에는 메타데이터만 공개하고 사진 조회는 기존 viewer 정책을 유지한다. */
    @Transactional(readOnly = true)
    fun requireMetadataViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (!isManager(gallery, userId) && !isPersonalParticipant(gallery, userId)) findMember(galleryId, userId)
        return gallery
    }

    /**
     * 초대 수락.
     */
    @Transactional(readOnly = true)
    fun requireNotManager(galleryId: Long, userId: Long) {
        val gallery = galleryRepository.requireById(galleryId)
        if (isManager(gallery, userId)) {
            throw GalleryException(GalleryErrorCode.MANAGER_CANNOT_ACCEPT_INVITE)
        }
    }

    /**
     * 선택 앨범의 담기·빼기·제출, 협업 세션 개설과 큐레이션.
     */
    @Transactional(readOnly = true)
    fun requireSelectionEditor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        val studioMember = workspace.type == WorkspaceType.STUDIO &&
            workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, userId) != null
        val invited = galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId) != null
        val personalParticipant = workspace.type == WorkspaceType.PERSONAL && isPersonalParticipant(gallery, userId)
        if (studioMember || (!invited && !personalParticipant)) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        requireSelectable(gallery, personal = workspace.type == WorkspaceType.PERSONAL)
        return gallery
    }

    /** 부부의 내부 대화는 작가에게 공개하지 않는다. 마감 이후에도 대화 조회와 본인 삭제는 남긴다. */
    @Transactional(readOnly = true)
    fun requireParticipantViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        requireParticipant(gallery, userId)
        return gallery
    }

    /** 댓글은 선택 결과를 바꾸지 않으므로 제출 여부와 무관하지만, 갤러리의 의견 작성 기한은 지킨다. */
    @Transactional
    fun requireParticipantWriter(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val workspace = requireParticipant(gallery, userId)
        requireSelectable(gallery, personal = workspace.type == WorkspaceType.PERSONAL)
        return gallery
    }

    /** 부부(초대 고객·개인 갤러리 참여자)인지 확인하고, 판단에 쓴 작업공간을 돌려준다. */
    private fun requireParticipant(gallery: Gallery, userId: Long): Workspace {
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        val membership = workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, userId)
        // PERSONAL_PARTNER 초대 수락은 gallery_members가 아니라 작업공간 MEMBER를 만든다.
        val personalParticipant = workspace.type == WorkspaceType.PERSONAL && membership != null &&
            (membership.role == WorkspaceRole.MEMBER || workspace.personalOwnerUserId == userId)
        val invited = galleryMemberRepository.findByGalleryIdAndUserId(gallery.requiredId, userId) != null
        // 운영 정지로 관리 기능이 막혀도 직원의 신분이 부부로 바뀌지는 않는다.
        val studioManager = workspace.type == WorkspaceType.STUDIO && membership != null
        if (studioManager || (!personalParticipant && !invited) || !gallery.isVisibleToMember) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return workspace
    }

    /** 게스트 의견과 링크는 클라이언트의 사적 협업 정보다. 작가에게는 공개하지 않는다. */
    @Transactional
    fun requireCollabManager(galleryId: Long, userId: Long, writable: Boolean): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        requireParticipant(gallery, userId)
        if (writable) gallery.requireWritable(ZonedDateTime.now(clock))
        return gallery
    }

    /**
     * 갤러리 상세, 사진 목록, 선택 앨범 조회, 협업 결과 조회.
     */
    @Transactional(readOnly = true)
    fun requireViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (isManager(gallery, userId) || isPersonalParticipant(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        // DRAFT는 아직 초대받은 사람에게 보이지 않는다.
        if (!gallery.isVisibleToMember) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
        return gallery
    }

    /**
     * 폴더 전반, 사진 상세, 별점 주기/지우기.
     */
    @Transactional(readOnly = true)
    fun requireManagerOrSelectionEditor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (isManager(gallery, userId) || isPersonalParticipant(gallery, userId)) {
            return gallery
        }
        return requireSelectionEditor(galleryId, userId)
    }

    private fun isManager(gallery: Gallery, userId: Long): Boolean {
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null) ?: return false
        if (workspace.type == WorkspaceType.STUDIO &&
            !studioRepository.existsByIdAndSuspendedAtIsNull(gallery.workspaceId)
        ) return false
        if (workspace.type == WorkspaceType.PERSONAL) return workspace.personalOwnerUserId == userId &&
            workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(gallery.workspaceId, userId, listOf(WorkspaceRole.OWNER))
        return workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                gallery.workspaceId,
                userId,
                ACTIVE_WORKSPACE_ROLES,
            )
    }

    private fun isPersonalParticipant(gallery: Gallery, userId: Long): Boolean {
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null) ?: return false
        return workspace.type == WorkspaceType.PERSONAL &&
            workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(gallery.workspaceId, userId, ACTIVE_WORKSPACE_ROLES)
    }

    private fun findMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)

    /**
     * 개인 갤러리의 `selectionDeadline`은 부부가 스스로 정한 목표일이라 D-day를 셀 뿐 고르기를 막지 않는다.
     * 개인 갤러리가 닫히는 때는 이용 기간 만료와 갤러리 마무리(보관)뿐이고, 둘 다 [Gallery.requireWritable]이 본다.
     */
    private fun requireSelectable(gallery: Gallery, personal: Boolean) {
        gallery.requireWritable(ZonedDateTime.now(clock))
        if (!personal && gallery.isDeadlinePassed(ZonedDateTime.now(clock))) {
            throw GalleryException(GalleryErrorCode.SELECTION_DEADLINE_PASSED)
        }
        if (gallery.status != GalleryStatus.OPEN) {
            throw GalleryException(GalleryErrorCode.GALLERY_NOT_OPEN)
        }
    }

    companion object {
        private val ACTIVE_WORKSPACE_ROLES = WorkspaceRole.entries
    }
}
