package com.soma.wes.gallery.fixture

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Component

@Component
class PersonalGalleryFixture(
    private val users: UserFixture,
    private val workspaces: WorkspaceRepository,
    private val members: WorkspaceMemberRepository,
    private val galleries: GalleryRepository,
) {
    fun 파트너와_개인_갤러리(target: Int = 2): PersonalGallery {
        val owner = users.사용자()
        val partner = users.사용자()
        val workspace = workspaces.findByPersonalOwnerUserId(owner.requiredId)!!
        members.save(WorkspaceMember(workspaceId = workspace.requiredId, userId = partner.requiredId, role = WorkspaceRole.MEMBER))
        val gallery = galleries.save(Gallery(workspaceId = workspace.requiredId, createdByUserId = owner.requiredId,
            title = "개인 갤러리", status = GalleryStatus.OPEN, maxSelectablePhotoCount = target))
        return PersonalGallery(galleryId = gallery.requiredId, ownerId = owner.requiredId, partnerId = partner.requiredId)
    }
}

data class PersonalGallery(val galleryId: Long, val ownerId: Long, val partnerId: Long)
