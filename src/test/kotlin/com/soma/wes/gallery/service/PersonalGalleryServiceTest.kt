package com.soma.wes.gallery.service

import com.soma.wes.billing.domain.TestCheckout
import com.soma.wes.billing.exception.BillingErrorCode
import com.soma.wes.billing.exception.BillingException
import com.soma.wes.billing.repository.TestCheckoutRepository
import com.soma.wes.gallery.domain.GalleryStage
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.dto.request.UpdatePersonalGalleryRequest
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZonedDateTime

@IntegrationTest
class PersonalGalleryServiceTest @Autowired constructor(
    private val target: PersonalGalleryService,
    private val userFixture: UserFixture,
    private val checkoutRepository: TestCheckoutRepository,
    private val members: WorkspaceMemberRepository,
    private val policy: GalleryAccessPolicy,
) {
    private fun checkout(userId: Long, expired: Boolean = false): TestCheckout = checkoutRepository.save(TestCheckout(
        userId = userId, planId = "test-plan", amount = 0, currency = "KRW", maxPhotoCount = 100,
        expiresAt = if (expired) ZonedDateTime.now().minusDays(1) else ZonedDateTime.now().plusDays(30),
    ))

    @Test
    fun `결제를 소비해 바로 접근 가능한 개인 갤러리를 만든다`() {
        // given
        val user = userFixture.사용자()
        val payment = checkout(user.requiredId)
        // when
        val result = target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "우리 사진", maxSelectablePhotoCount = 20))
        // then
        assertThat(result.status).isEqualTo(GalleryStatus.OPEN)
        assertThat(result.stage).isEqualTo(GalleryStage.UPLOAD)
        assertThat(result.photoOrganizationRequired).isTrue()
        assertThat(result.planMaxPhotoCount).isEqualTo(100)
        assertThat(checkoutRepository.findById(payment.id).orElseThrow().galleryId).isEqualTo(result.id)
    }

    @Test
    fun `다른 사람 결제나 만료된 결제로 개설할 수 없다`() {
        // given
        val user = userFixture.사용자()
        val other = userFixture.사용자()
        val foreign = checkout(other.requiredId)
        val expired = checkout(user.requiredId, expired = true)
        // when & then
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(foreign.id, "외부 결제")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_NOT_FOUND)
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(expired.id, "만료 결제")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_EXPIRED)
    }

    @Test
    fun `사용한 결제로 갤러리를 두 번 만들지 않는다`() {
        // given
        val user = userFixture.사용자()
        val payment = checkout(user.requiredId)
        target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "첫 갤러리"))
        // when & then
        assertThatThrownBy { target.create(user.requiredId, CreatePersonalGalleryRequest(payment.id, "두 번째")) }
            .isInstanceOf(BillingException::class.java).extracting("errorCode").isEqualTo(BillingErrorCode.CHECKOUT_ALREADY_USED)
    }

    @Test
    fun `파트너는 업로드와 선택은 하지만 설정을 수정하지 못한다`() {
        // given
        val owner = userFixture.사용자()
        val partner = userFixture.사용자()
        val gallery = target.create(owner.requiredId, CreatePersonalGalleryRequest(checkout(owner.requiredId).id, "함께 고르기"))
        members.save(WorkspaceMember(workspaceId = gallery.workspaceId, userId = partner.requiredId, role = WorkspaceRole.MEMBER))
        // when & then
        assertThat(policy.requireUploader(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
        assertThat(policy.requireSelectionEditor(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
        assertThat(policy.requireRetouchProcessor(gallery.id, partner.requiredId).requiredId).isEqualTo(gallery.id)
        assertThatThrownBy { target.update(gallery.id, partner.requiredId, UpdatePersonalGalleryRequest("바꾼 이름")) }
            .isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }
}
