package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryWorkflowStatus
import com.soma.wes.gallery.dto.request.ChangeWorkflowStatusRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.RenameGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.studio.fixture.StudioFixture
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class GalleryServiceTest @Autowired constructor(
    private val galleryService: GalleryService,
    private val galleryFixture: GalleryFixture,
    private val studioFixture: StudioFixture,
    private val userFixture: UserFixture,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
) {
    @Test
    fun `PERSONAL 작업공간에도 갤러리를 만들 수 있다`() {
        val user = userFixture.사용자()
        val personal = workspaceRepository.findByPersonalOwnerUserId(user.requiredId)!!

        val result = galleryService.create(
            user.requiredId,
            CreateGalleryRequest(personal.requiredId, "개인 본식", maxRetouchRoundCount = 3),
        )

        assertThat(result.workspaceId).isEqualTo(personal.requiredId)
        assertThat(result.createdByUserId).isEqualTo(user.requiredId)
        assertThat(result.status).isEqualTo(GalleryStatus.DRAFT)
        assertThat(result.workflowStatus).isEqualTo(GalleryWorkflowStatus.DRAFT)
        assertThat(result.stage).isEqualTo(GalleryStage.UPLOAD)
        assertThat(result.maxRetouchRoundCount).isEqualTo(3)
    }

    @Test
    fun `갤러리 목록은 6단계 진행 상태로 필터링한다`() {
        val owner = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(owner)
        val upload = galleryService.create(owner.requiredId, CreateGalleryRequest(studio.workspaceId, "업로드"))
        val selecting = galleryService.create(owner.requiredId, CreateGalleryRequest(studio.workspaceId, "선택"))
        galleryService.open(selecting.id, owner.requiredId)

        assertThat(galleryService.findAllVisibleTo(owner.requiredId, GalleryStage.UPLOAD).map { it.id })
            .containsExactly(upload.id)
        assertThat(galleryService.findAllVisibleTo(owner.requiredId, GalleryStage.SELECTION_IN_PROGRESS).map { it.id })
            .containsExactly(selecting.id)
    }

    @Test
    fun `STUDIO 멤버는 명시한 작업공간에 갤러리를 만들고 관리한다`() {
        val owner = studioFixture.작가()
        val studio = studioFixture.소유_스튜디오(owner)
        val member = userFixture.사용자()
        workspaceMemberRepository.save(
            WorkspaceMember(studio.workspaceId, member.requiredId, WorkspaceRole.MEMBER),
        )

        val created = galleryService.create(
            member.requiredId,
            CreateGalleryRequest(studio.workspaceId, "멤버가 생성"),
        )
        val renamed = galleryService.rename(
            created.id,
            member.requiredId,
            RenameGalleryRequest("멤버가 수정"),
        )

        assertThat(created.workspaceId).isEqualTo(studio.workspaceId)
        assertThat(renamed.title).isEqualTo("멤버가 수정")
    }

    @Test
    fun `소속되지 않은 작업공간을 소유자가 다르다는 이유로 사용할 수 없다`() {
        val firstOwner = studioFixture.작가()
        val secondOwner = studioFixture.작가()
        val secondStudio = studioFixture.소유_스튜디오(secondOwner)

        assertThatThrownBy {
            galleryService.create(
                firstOwner.requiredId,
                CreateGalleryRequest(secondStudio.workspaceId, "남의 갤러리"),
            )
        }.isInstanceOf(com.soma.wes.studio.exception.StudioException::class.java)
    }

    @Test
    fun `고객 노출 상태와 제작 워크플로 상태는 독립적이다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()

        val changed = galleryService.changeWorkflowStatus(
            fixture.galleryId,
            fixture.photographer.requiredId,
            ChangeWorkflowStatusRequest(GalleryWorkflowStatus.IN_PROGRESS),
        )

        assertThat(changed.status).isEqualTo(GalleryStatus.OPEN)
        assertThat(changed.workflowStatus).isEqualTo(GalleryWorkflowStatus.IN_PROGRESS)
    }

    @Test
    fun `초대 멤버는 갤러리 관리 정보를 바꾸지 못한다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()

        assertThatThrownBy {
            galleryService.rename(fixture.galleryId, fixture.member.requiredId, RenameGalleryRequest("고객 수정"))
        }.isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }
}
