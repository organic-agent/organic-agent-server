package com.soma.wes.collab.service

import com.soma.wes.category.dto.request.CreateConceptFolderRequest
import com.soma.wes.category.service.CategoryService
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.fixture.SharedCollab
import com.soma.wes.gallery.domain.GalleryStatus
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import java.time.ZonedDateTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class CollabSessionServiceTest @Autowired constructor(
    private val service: CollabSessionService,
    private val queryService: CollabSessionQueryService,
    private val categoryService: CategoryService,
    private val collabFixture: CollabFixture,
    private val galleryFixture: GalleryFixture,
    private val userFixture: UserFixture,
    private val galleryRepository: GalleryRepository,
    private val memberRepository: GalleryMemberRepository,
) {
    private lateinit var shared: SharedCollab

    @BeforeEach
    fun setUpBaseData() {
        shared = collabFixture.사진이_있는_세션()
    }

    @Nested
    @DisplayName("초대 부부가 협업 링크를 관리할 때")
    inner class Management {
        @Test
        fun `부부가 컨셉 링크를 열고 서로 같은 세션을 재사용한다`() {
            // given
            val partner = galleryFixture.멤버(shared.galleryId)
            val concept = categoryService.createConcept(
                shared.galleryId, shared.gallery.member.requiredId, CreateConceptFolderRequest(name = "새 컨셉"),
            )

            // when
            val opened = service.open(
                shared.galleryId, shared.gallery.member.requiredId,
                OpenCollabSessionRequest(conceptFolderId = concept.id, name = "부모님 의견"),
            )
            val reused = service.open(
                shared.galleryId, partner.requiredId,
                OpenCollabSessionRequest(conceptFolderId = concept.id, name = "가족 의견"),
            )

            // then
            assertSoftly { softly ->
                softly.assertThat(reused.sessionId).isEqualTo(opened.sessionId)
                softly.assertThat(reused.collabUrl).isEqualTo(opened.collabUrl)
                softly.assertThat(reused.name).isEqualTo("가족 의견")
                softly.assertThat(reused.conceptFolderId).isEqualTo(concept.id)
            }
        }

        @Test
        fun `부부가 링크를 폐기하고 새 토큰으로 재발행한다`() {
            // given
            val partner = galleryFixture.멤버(shared.galleryId)

            // when
            service.revoke(shared.galleryId, shared.session.sessionId, shared.gallery.member.requiredId)
            val republished = service.republish(shared.galleryId, shared.session.sessionId, partner.requiredId)

            // then
            assertSoftly { softly ->
                softly.assertThat(republished.revoked).isFalse()
                softly.assertThat(republished.revokedAt).isNull()
                softly.assertThat(republished.collabUrl).isNotEqualTo(shared.session.collabUrl)
                softly.assertThat(republished.photoCount).isEqualTo(1L)
            }
        }

        @Test
        fun `선택 마감 이후에도 클라이언트는 조회용 공유 링크를 다시 발급할 수 있다`() {
            // given
            galleryFixture.마감_지남(shared.galleryId)
            val userId = shared.gallery.member.requiredId

            // when
            val renamed = service.rename(
                shared.galleryId, shared.session.sessionId, userId, RenameCollabSessionRequest(name = "종료한 의견"),
            )
            service.revoke(shared.galleryId, shared.session.sessionId, userId)

            // then
            assertThat(renamed.name).isEqualTo("종료한 의견")
            assertThat(queryService.get(shared.galleryId, shared.session.sessionId, userId).revoked).isTrue()
            assertThat(service.republish(shared.galleryId, shared.session.sessionId, userId).revoked).isFalse()
        }

        @Test
        fun `보관된 갤러리의 기존 링크 정리는 허용하지만 새 발행은 거절한다`() {
            // given
            val gallery = galleryRepository.findById(shared.galleryId).orElseThrow()
            gallery.close()
            galleryRepository.saveAndFlush(gallery)
            val userId = shared.gallery.member.requiredId

            // when
            assertThatThrownBy {
                service.rename(shared.galleryId, shared.session.sessionId, userId, RenameCollabSessionRequest(name = "끝난 링크"))
            }.isInstanceOf(GalleryException::class.java).extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ARCHIVED)
            service.revoke(shared.galleryId, shared.session.sessionId, userId)

            // then
            assertThat(queryService.get(shared.galleryId, shared.session.sessionId, userId).revoked).isTrue()
            assertPublicationDenied(userId, GalleryErrorCode.GALLERY_ARCHIVED)
        }

        @Test
        fun `스튜디오 작가는 클라이언트의 공유 링크를 발급하거나 관리할 수 없다`() {
            assertAllMutationsDenied(shared.gallery.photographer.requiredId, GalleryErrorCode.GALLERY_ACCESS_DENIED)
            galleryFixture.마감_지남(shared.galleryId)
            assertAllMutationsDenied(shared.gallery.photographer.requiredId, GalleryErrorCode.GALLERY_ACCESS_DENIED)
        }

    }

    @Nested
    @DisplayName("링크 관리 권한을 검증할 때")
    inner class AccessBoundary {
        @Test
        fun `외부 사용자와 제거된 부부 멤버는 관리할 수 없다`() {
            // given
            val stranger = userFixture.사용자()
            val member = memberRepository.findByGalleryIdAndUserId(shared.galleryId, shared.gallery.member.requiredId)!!
            member.deletedAt = ZonedDateTime.now()
            memberRepository.saveAndFlush(member)

            // when & then
            listOf(stranger.requiredId, shared.gallery.member.requiredId).forEach { userId ->
                assertAllMutationsDenied(userId, GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }

        @Test
        fun `DRAFT 갤러리의 부부는 관리할 수 없다`() {
            // given
            val gallery = galleryRepository.findById(shared.galleryId).orElseThrow()
            gallery.status = GalleryStatus.DRAFT
            galleryRepository.saveAndFlush(gallery)
            val userId = shared.gallery.member.requiredId

            // when & then
            assertPublicationDenied(userId, GalleryErrorCode.GALLERY_ACCESS_DENIED)
            listOf<() -> Unit>(
                { service.rename(shared.galleryId, shared.session.sessionId, userId, RenameCollabSessionRequest(name = "변경")) },
                { service.revoke(shared.galleryId, shared.session.sessionId, userId) },
            ).forEach { mutation ->
                assertThatThrownBy { mutation() }.isInstanceOf(GalleryException::class.java)
                    .extracting("errorCode").isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
            }
        }

        @Test
        fun `자기 갤러리 경로로 다른 갤러리 세션을 변경할 수 없다`() {
            // given
            val other = collabFixture.사진이_있는_세션()
            val userId = shared.gallery.member.requiredId

            // when & then
            listOf<() -> Unit>(
                { service.rename(shared.galleryId, other.session.sessionId, userId, RenameCollabSessionRequest(name = "변경")) },
                { service.revoke(shared.galleryId, other.session.sessionId, userId) },
                { service.republish(shared.galleryId, other.session.sessionId, userId) },
            ).forEach { mutation ->
                assertThatThrownBy { mutation() }.isInstanceOf(CollabException::class.java)
                    .extracting("errorCode").isEqualTo(CollabErrorCode.SESSION_NOT_FOUND)
            }
        }
    }

    private fun assertAllMutationsDenied(userId: Long, errorCode: GalleryErrorCode) {
        assertPublicationDenied(userId, errorCode)
        listOf<() -> Unit>(
            { service.rename(shared.galleryId, shared.session.sessionId, userId, RenameCollabSessionRequest(name = "변경")) },
            { service.revoke(shared.galleryId, shared.session.sessionId, userId) },
        ).forEach { mutation ->
            assertThatThrownBy { mutation() }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(errorCode)
        }
    }

    private fun assertPublicationDenied(userId: Long, errorCode: GalleryErrorCode) {
        listOf<() -> Unit>(
            {
                service.open(
                    shared.galleryId, userId,
                    OpenCollabSessionRequest(conceptFolderId = shared.session.conceptFolderId, name = "발행"),
                )
            },
            { service.republish(shared.galleryId, shared.session.sessionId, userId) },
        ).forEach { mutation ->
            assertThatThrownBy { mutation() }.isInstanceOf(GalleryException::class.java)
                .extracting("errorCode").isEqualTo(errorCode)
        }
    }
}
