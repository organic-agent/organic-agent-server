package com.soma.wes.recommendation.service

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.category.service.CategorizationService
import com.soma.wes.category.service.FolderOrganizationService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.FakeStructuredLlmClient
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class RecommendationActivityTest @Autowired constructor(
    private val activity: ActivityRepository,
    private val galleries: GalleryFixture,
    private val galleryRepository: GalleryRepository,
    private val photos: PhotoFixture,
    private val recommendations: RecommendationFixture,
    private val aiFolders: AiCategoryFolderService,
    private val organization: FolderOrganizationService,
    private val categorization: CategorizationService,
    private val service: AiRecommendationService,
    private val runner: AiSelectionJobRunner,
    private val llm: FakeStructuredLlmClient,
    private val jdbc: JdbcTemplate,
    private val transactions: PlatformTransactionManager,
) {
    private fun prepared(): OpenGallery {
        val fixture = galleries.멤버와_열린_갤러리()
        photos.임베딩된_사진(fixture.galleryId, 2).forEach { recommendations.분석_결과(it, embedGroupId = 1) }
        val job = recommendations.분석_잡(fixture.galleryId)
        recommendations.컨셉_배정(job, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
        return fixture
    }

    private fun clearActivity(fixture: OpenGallery) {
        jdbc.update("DELETE FROM gallery_activity WHERE gallery_id = ?", fixture.galleryId)
        jdbc.update("DELETE FROM workspace_activity WHERE workspace_id = ?", workspaceId(fixture))
    }

    private fun workspaceId(fixture: OpenGallery) = galleryRepository.findById(fixture.galleryId).orElseThrow().workspaceId

    private fun assertRecorded(fixture: OpenGallery) {
        assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).containsKey(fixture.galleryId)
        assertThat(activity.findWorkspaceActivity(listOf(workspaceId(fixture)))).containsKey(workspaceId(fixture))
    }

    @Test
    fun `AI 폴더 실제 저장은 기록하지만 같은 결과 재조회는 기록하지 않는다`() {
        val fixture = prepared()
        aiFolders.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)
        assertRecorded(fixture)
        clearActivity(fixture)
        aiFolders.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)
        assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEmpty()
        // 재분류 실행은 결과가 같더라도 새로 성공한 잡을 저장하는 별도 명시적 작업이다.
        categorization.run(fixture.galleryId, fixture.photographer.requiredId)
        assertRecorded(fixture)
    }

    @Test
    fun `이미 만든 폴더를 확정하면 스튜디오 최근 활동에도 반영한다`() {
        val fixture = prepared()
        aiFolders.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)
        clearActivity(fixture)
        organization.save(fixture.galleryId, fixture.member.requiredId)
        assertRecorded(fixture)
    }

    @Test
    fun `추천 접수는 기록하지만 조회와 비동기 적재는 접수 시각을 바꾸지 않는다`() {
        val fixture = prepared()
        aiFolders.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)
        clearActivity(fixture)
        llm.reset()
        llm.isEnabled = false
        val job = service.request(fixture.galleryId, fixture.member.requiredId, AiRecommendationRequest())
        assertRecorded(fixture)
        val accepted = activity.findGalleryActivity(listOf(fixture.galleryId))
        val workspaceAccepted = activity.findWorkspaceActivity(listOf(workspaceId(fixture)))
        service.list(fixture.galleryId, fixture.member.requiredId, null)
        runner.run(job.jobId)
        assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEqualTo(accepted)
        assertThat(activity.findWorkspaceActivity(listOf(workspaceId(fixture)))).isEqualTo(workspaceAccepted)
    }

    @Test
    fun `폴더 저장과 추천 접수가 롤백되면 활동도 남지 않는다`() {
        val fixture = prepared()
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            aiFolders.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)
            service.request(fixture.galleryId, fixture.member.requiredId, AiRecommendationRequest())
            assertRecorded(fixture)
            tx.setRollbackOnly()
        }
        assertThat(activity.findGalleryActivity(listOf(fixture.galleryId))).isEmpty()
        assertThat(activity.findWorkspaceActivity(listOf(workspaceId(fixture)))).isEmpty()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM concept_folders WHERE gallery_id = ?", Long::class.java, fixture.galleryId)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_selection_jobs", Long::class.java)).isZero()
    }
}
