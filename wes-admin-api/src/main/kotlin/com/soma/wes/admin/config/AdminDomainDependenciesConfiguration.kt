package com.soma.wes.admin.config

import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.collab.config.CollabProperties
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.category.service.CategorizationService
import com.soma.wes.category.service.AiCategoryFolderService
import com.soma.wes.category.support.AiCategoryFolderPlanner
import com.soma.wes.global.config.AwsLambdaConfig
import com.soma.wes.analysis.config.EmbeddingProperties
import com.soma.wes.analysis.infrastructure.LambdaEmbeddingInvoker
import com.soma.wes.folder.service.PhotoFolderGroupService
import com.soma.wes.folder.support.FolderPhotoLoader
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.gallery.config.GalleryInviteProperties
import com.soma.wes.gallery.config.MockGalleryProperties
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.global.config.SchedulingConfig
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.global.config.WebMvcConfig
import com.soma.wes.global.exception.GlobalExceptionHandler
import com.soma.wes.photo.config.StorageProperties
import com.soma.wes.photo.infrastructure.S3PhotoStorage
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.analysis.support.AiConceptAssignmentLoader
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.retouch.support.RetouchPhotoLoader
import com.soma.wes.retouch.support.RetouchResultLoader
import com.soma.wes.retouch.support.RetouchViewAssembler
import com.soma.wes.security.exception.CustomAccessDeniedHandler
import com.soma.wes.security.exception.CustomAuthenticationEntryPoint
import com.soma.wes.selection.service.PhotoSelectionService
import com.soma.wes.trash.config.TrashProperties
import com.soma.wes.trash.repository.ProductChildTrashRepository
import com.soma.wes.trash.service.ProductChildTrashService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * 관리자 런타임이 실제로 재사용하는 제품 정책과 어댑터만 명시적으로 연결한다.
 *
 * 패키지 전체를 스캔하지 않는 이유는 공개 OAuth/JWT/Swagger와 사용자용 스케줄러가
 * 관리자 전용 설정과 생명주기에 섞이지 않게 하기 위해서다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    AdminAuthProperties::class,
    AdminObservabilityProperties::class,
    AdminWorkflowExecutorProperties::class,
    StorageProperties::class,
    TrashProperties::class,
    EmbeddingProperties::class,
    MockGalleryProperties::class,
    GalleryInviteProperties::class,
    CollabProperties::class,
)
@Import(
    TimeConfig::class,
    SchedulingConfig::class,
    WebMvcConfig::class,
    GlobalExceptionHandler::class,
    CustomAuthenticationEntryPoint::class,
    CustomAccessDeniedHandler::class,
    SecureTokenGenerator::class,
    S3PhotoStorage::class,
    AwsLambdaConfig::class,
    LambdaEmbeddingInvoker::class,
    GalleryInviteUrlResolver::class,
    CollabLinkResolver::class,
    GalleryAccessPolicy::class,
    GalleryService::class,
    PhotoService::class,
    PhotoSelectionService::class,
    CollabSessionQueryService::class,
    PhotoFolderGroupService::class,
    RetouchService::class,
    PhotoViewAssembler::class,
    CollabPhotoViewAssembler::class,
    FolderPhotoLoader::class,
    FolderViewAssembler::class,
    RetouchPhotoLoader::class,
    RetouchResultLoader::class,
    RetouchViewAssembler::class,
    ProductChildTrashRepository::class,
    ProductChildTrashService::class,
    AiCategoryFolderPlanner::class,
    AiConceptAssignmentLoader::class,
    AiCategoryFolderService::class,
    CategorizationService::class,
)
class AdminDomainDependenciesConfiguration {
    /**
     * BackOffice는 사용자 알림과 설정을 읽기 전용 운영 정보로만 노출한다. 관리자 mutation은
     * 관리자 감사/inbox에만 기록하며 제품 사용자 알림을 자동 생성하지 않는다.
     */
    @Bean
    fun userNotificationPublisher(): UserNotificationPublisher = UserNotificationPublisher { _, _, _, _, _, _ -> }
}
