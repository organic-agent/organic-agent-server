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
    fun requireCategoryEditor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireWithLockById(galleryId)
        if (isManager(gallery, userId)) {
            return gallery
        }

        findMember(galleryId, userId)
        requireSelectable(gallery)
        photoSelectionRepository.findByGalleryId(galleryId)?.requireEditable()
        return gallery
    }

    /** 보정 결과 업로드·완료는 STUDIO 작업공간 구성원만 담당한다. */
    @Transactional(readOnly = true)
    fun requireRetouchProcessor(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        if (workspace.type != WorkspaceType.STUDIO ||
            !studioRepository.existsByIdAndSuspendedAtIsNull(gallery.workspaceId) ||
            !workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                gallery.workspaceId,
                userId,
                ACTIVE_WORKSPACE_ROLES,
            )
        ) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }
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
        val invited = galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId) != null
        val personalOwner = workspace.type == WorkspaceType.PERSONAL && isManager(gallery, userId)
        if (!invited && !personalOwner) {
            throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

        requireSelectable(gallery)
        return gallery
    }

    /**
     * 갤러리 상세, 사진 목록, 선택 앨범 조회, 협업 결과 조회.
     */
    @Transactional(readOnly = true)
    fun requireViewer(galleryId: Long, userId: Long): Gallery {
        val gallery = galleryRepository.requireById(galleryId)
        if (isManager(gallery, userId)) {
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
        if (isManager(gallery, userId)) {
            return gallery
        }
        return requireSelectionEditor(galleryId, userId)
    }

    private fun isManager(gallery: Gallery, userId: Long): Boolean {
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElse(null) ?: return false
        if (workspace.type == WorkspaceType.STUDIO &&
            !studioRepository.existsByIdAndSuspendedAtIsNull(gallery.workspaceId)
        ) return false
        return workspaceMemberRepository.existsByWorkspaceIdAndUserIdAndRoleIn(
                gallery.workspaceId,
                userId,
                ACTIVE_WORKSPACE_ROLES,
            )
    }

    private fun findMember(galleryId: Long, userId: Long): GalleryMember =
        galleryMemberRepository.findByGalleryIdAndUserId(galleryId, userId)
            ?: throw GalleryException(GalleryErrorCode.GALLERY_ACCESS_DENIED)

    private fun requireSelectable(gallery: Gallery) {
        if (gallery.isDeadlinePassed(ZonedDateTime.now(clock))) {
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
