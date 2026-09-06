package com.soma.wes.analysis.service

import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.domain.AnalysisMode
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.fixture.PersonalGalleryFixture
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.notification.service.UserNotificationService
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class AnalysisCompletionNotificationServiceTest @Autowired constructor(
    private val completion: AnalysisCompletionNotificationService,
    private val jobs: AnalysisJobRepository,
    private val galleries: GalleryRepository,
    private val workspaceMembers: WorkspaceMemberRepository,
    private val galleryMembers: GalleryMemberRepository,
    private val notifications: UserNotificationService,
    private val personalGalleries: PersonalGalleryFixture,
    private val users: UserFixture,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {
    private fun done(galleryId: Long, mode: AnalysisMode = AnalysisMode.FULL): AnalysisJob =
        jobs.saveAndFlush(AnalysisJob(galleryId, mode).apply { finish(ZonedDateTime.now(clock)) })

    private fun completionCount(userId: Long): Int = notifications.list(userId, null, null)
        .count { it.type == UserNotificationType.ANALYSIS_COMPLETED }

    @Test
    fun `개인 소유자와 파트너는 중복 소속에도 한 번씩 받고 다음 분석은 새 완료로 알린다`() {
        val fixture = personalGalleries.파트너와_개인_갤러리()
        val outsider = users.사용자()
        galleryMembers.save(GalleryMember(fixture.galleryId, fixture.partnerId))
        val first = done(fixture.galleryId)
        completion.publishCompleted(first.requiredId)
        completion.sweep()
        for (userId in listOf(fixture.ownerId, fixture.partnerId)) assertThat(completionCount(userId)).isEqualTo(1)
        assertThat(completionCount(outsider.requiredId)).isZero()

        val second = done(fixture.galleryId, AnalysisMode.NAMING)
        completion.sweep()
        for (userId in listOf(fixture.ownerId, fixture.partnerId)) assertThat(completionCount(userId)).isEqualTo(2)
        assertThat(jobs.findById(second.requiredId).orElseThrow().completionNotifiedAt).isNotNull()
        assertThat(notifications.list(fixture.ownerId, null, null).map { it.message })
            .contains("AI 컨셉 분류가 완료되었습니다. 분류 결과를 확인해 주세요.")
    }

    @Test
    fun `동시에 처리한 완료 잡은 수신자별 알림을 한 번만 저장한다`() {
        val fixture = personalGalleries.파트너와_개인_갤러리()
        val job = done(fixture.galleryId)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit {
                    check(start.await(10, TimeUnit.SECONDS))
                    completion.publishCompleted(job.requiredId)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertThat(completionCount(fixture.ownerId)).isEqualTo(1)
        assertThat(completionCount(fixture.partnerId)).isEqualTo(1)
        assertThat(jobs.findById(job.requiredId).orElseThrow().completionNotifiedAt).isNotNull()
    }

    @Test
    fun `알림 저장 실패는 완료 마커와 알림을 함께 롤백하고 다음 스윕이 다시 처리한다`() {
        val fixture = personalGalleries.파트너와_개인_갤러리()
        val job = done(fixture.galleryId)
        val failing = AnalysisCompletionNotificationService(
            jobs, galleries, workspaceMembers, galleryMembers,
            UserNotificationPublisher { userIds, type, scope, scopeId, title, message ->
                notifications.publish(userIds, type, scope, scopeId, title, message)
                error("저장 중 장애")
            },
            transactionTemplate, clock,
        )
        assertThatThrownBy { failing.publishCompleted(job.requiredId) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jobs.findById(job.requiredId).orElseThrow().completionNotifiedAt).isNull()
        assertThat(completionCount(fixture.ownerId)).isZero()
        assertThat(completionCount(fixture.partnerId)).isZero()
        completion.sweep()
        assertThat(completionCount(fixture.ownerId)).isEqualTo(1)
        assertThat(completionCount(fixture.partnerId)).isEqualTo(1)
    }

    @Test
    fun `휴지통의 갤러리는 완료 알림 후보에서 빠져 다음 갤러리 처리를 막지 않는다`() {
        val trashed = personalGalleries.파트너와_개인_갤러리()
        done(trashed.galleryId)
        val gallery = galleries.findById(trashed.galleryId).orElseThrow()
        gallery.moveToTrash(ZonedDateTime.now(clock))
        galleries.saveAndFlush(gallery)
        val active = personalGalleries.파트너와_개인_갤러리()
        val activeJob = done(active.galleryId)
        assertThat(jobs.findAwaitingCompletionNotification(org.springframework.data.domain.PageRequest.of(0, 1)))
            .containsExactly(activeJob.requiredId)
        completion.sweep()
        assertThat(completionCount(trashed.ownerId)).isZero()
        assertThat(completionCount(active.ownerId)).isEqualTo(1)
    }

    @Test
    fun `완료 알림 스윕은 백 건씩 처리하고 실패하거나 진행 중인 잡은 알리지 않는다`() {
        val fixture = personalGalleries.파트너와_개인_갤러리()
        val completed = jobs.saveAll((1..101).map {
            AnalysisJob(fixture.galleryId, AnalysisMode.FULL).apply { finish(ZonedDateTime.now(clock)) }
        })
        val failed = jobs.saveAndFlush(AnalysisJob(fixture.galleryId, AnalysisMode.FULL).apply {
            fail("분석 실패", ZonedDateTime.now(clock))
        })
        val pending = jobs.saveAndFlush(AnalysisJob(fixture.galleryId, AnalysisMode.FULL))
        completion.sweep()
        assertThat(completionCount(fixture.ownerId)).isEqualTo(100)
        assertThat(jobs.findById(completed.last().requiredId).orElseThrow().completionNotifiedAt).isNull()
        completion.sweep()
        assertThat(completionCount(fixture.ownerId)).isEqualTo(101)
        assertThat(jobs.findById(failed.requiredId).orElseThrow().completionNotifiedAt).isNull()
        assertThat(jobs.findById(pending.requiredId).orElseThrow().completionNotifiedAt).isNull()
    }
}
