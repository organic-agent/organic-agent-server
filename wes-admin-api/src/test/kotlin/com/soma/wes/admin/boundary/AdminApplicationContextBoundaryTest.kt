package com.soma.wes.admin.boundary

import com.soma.wes.WesAdminApiApplication
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.config.AdminDomainDependenciesConfiguration
import com.soma.wes.admin.config.AdminObservabilityProperties
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.auth.service.AuthTokenProvider
import com.soma.wes.auth.support.OAuthRegistrations
import com.soma.wes.auth.support.OAuthStateCleaner
import com.soma.wes.auth.token.config.JwtProperties
import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.category.service.CategorizationService
import com.soma.wes.category.service.CategoryService
import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.category.support.AiCategoryFolderPlanner
import com.soma.wes.collab.config.CollabProperties
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.collab.support.CollabCategoryReactionCleaner
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoMembership
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.global.config.AwsLambdaConfig
import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.infrastructure.LambdaEmbeddingInvoker
import com.soma.wes.gallery.config.GalleryLifecycleProperties
import com.soma.wes.gallery.config.GalleryInviteProperties
import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.global.config.RestClientConfig
import com.soma.wes.global.config.SchedulingConfig
import com.soma.wes.global.config.SwaggerConfig
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.global.config.WebMvcConfig
import com.soma.wes.global.exception.GlobalExceptionHandler
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.infrastructure.S3PhotoStorage
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.analysis.support.AiConceptAssignmentLoader
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.retouch.service.RetouchRequestService
import com.soma.wes.retouch.support.RetouchPhotoLoader
import com.soma.wes.retouch.support.RetouchResultLoader
import com.soma.wes.retouch.support.RetouchViewAssembler
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.ProductChildTrashRepository
import com.soma.wes.trash.service.ProductChildTrashService
import com.soma.wes.trash.support.TrashPurgeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@SpringBootTest(
    classes = [WesAdminApiApplication::class],
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        "app.admin.workflow-executor.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class)
class AdminApplicationContextBoundaryTest @Autowired constructor(
    private val context: ApplicationContext,
    private val environment: Environment,
) {

    @Test
    fun `admin context contains only the explicit product dependencies`() {
        assertPresent(
            AdminDomainDependenciesConfiguration::class.java,
            AdminAuthProperties::class.java,
            AdminObservabilityProperties::class.java,
            AdminWorkflowExecutorProperties::class.java,
            StorageProperties::class.java,
            TrashProperties::class.java,
            EmbeddingProperties::class.java,
            MockGalleryProperties::class.java,
            GalleryInviteProperties::class.java,
            GalleryLifecycleProperties::class.java,
            CollabProperties::class.java,
            AdminAccountRepository::class.java,
            AdminAuditLogRepository::class.java,
            GalleryRepository::class.java,
            StudioRepository::class.java,
            PhotoRepository::class.java,
            PhotoSelectionRepository::class.java,
            CollabSessionRepository::class.java,
            ConceptFolderRepository::class.java,
            RetouchRoundRepository::class.java,
            TimeConfig::class.java,
            SchedulingConfig::class.java,
            WebMvcConfig::class.java,
            GlobalExceptionHandler::class.java,
            CustomAuthenticationEntryPoint::class.java,
            CustomAccessDeniedHandler::class.java,
            SecureTokenGenerator::class.java,
            S3PhotoStorage::class.java,
            AwsLambdaConfig::class.java,
            LambdaEmbeddingInvoker::class.java,
            GalleryInviteUrlResolver::class.java,
            CollabLinkResolver::class.java,
            GalleryAccessPolicy::class.java,
            GalleryService::class.java,
            PhotoService::class.java,
            PhotoSelectionService::class.java,
            CollabSessionQueryService::class.java,
            CategoryService::class.java,
            CollabCategoryReactionCleaner::class.java,
            RetouchService::class.java,
            PhotoViewAssembler::class.java,
            CollabPhotoViewAssembler::class.java,
            CollabPhotoMembership::class.java,
            RetouchRequestService::class.java,
            RetouchPhotoLoader::class.java,
            RetouchResultLoader::class.java,
            RetouchViewAssembler::class.java,
            ProductChildTrashRepository::class.java,
            ProductChildTrashService::class.java,
            AiCategoryFolderPlanner::class.java,
            AiConceptAssignmentLoader::class.java,
            AiCategoryFolderService::class.java,
            CategorizationService::class.java,
        )

        assertAbsent(
            SwaggerConfig::class.java,
            RestClientConfig::class.java,
            AuthTokenProvider::class.java,
            OAuthRegistrations::class.java,
            OAuthStateCleaner::class.java,
            TrashPurgeScheduler::class.java,
            JwtProperties::class.java,
        )
    }

    @Test
    fun `all application request handlers belong to the admin API`() {
        val handlerMapping = context.getBean(
            "requestMappingHandlerMapping",
            RequestMappingHandlerMapping::class.java,
        )
        val applicationHandlers = handlerMapping.handlerMethods.values
            .map(HandlerMethod::getBeanType)
            .filter { it.packageName.startsWith("com.soma.wes") }

        assertThat(applicationHandlers).isNotEmpty()
        assertThat(applicationHandlers).allMatch { it.packageName.startsWith("com.soma.wes.admin") }
    }

    @Test
    fun `admin context never owns Flyway migration`() {
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean::class.java)).isFalse()
        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean::class.java)).isFalse()
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean::class.java)).isFalse()
        assertThat(context.getBeanNamesForType(Flyway::class.java)).isEmpty()
    }

    private fun assertPresent(vararg types: Class<*>) {
        val beans = context as ListableBeanFactory
        types.forEach { type ->
            assertThat(beans.getBeanNamesForType(type))
                .describedAs("required admin dependency %s", type.name)
                .isNotEmpty()
        }
    }

    private fun assertAbsent(vararg types: Class<*>) {
        val beans = context as ListableBeanFactory
        types.forEach { type ->
            assertThat(beans.getBeanNamesForType(type))
                .describedAs("forbidden public dependency %s", type.name)
                .isEmpty()
        }
    }
}
