package com.soma.wes.admin.config

import com.soma.wes.activity.repository.ActivityRepository
import com.soma.wes.activity.service.ActivityRecorder
import com.soma.wes.admin.resource.config.AdminWorkflowExecutorProperties
import com.soma.wes.collab.config.CollabProperties
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.collab.support.CollabFolderReactionCleaner
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoMembership
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.folder.service.FolderService
import com.soma.wes.folder.repository.DetailFolderAssignmentBulkRepository
import com.soma.wes.folder.support.AiFolderMaterializer
import com.soma.wes.folder.support.AiFolderPlanner
import com.soma.wes.folder.support.FolderViewAssembler
import com.soma.wes.global.config.AwsLambdaConfig
import com.soma.wes.analysis.config.LambdaAiTaskProperties
import com.soma.wes.analysis.infrastructure.LambdaAiTaskSender
import com.soma.wes.gallery.config.GalleryLifecycleProperties
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
import com.soma.wes.photo.repository.PhotoPipelineRepository
import com.soma.wes.photo.service.PhotoService
import com.soma.wes.photo.support.PhotoViewAssembler
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.analysis.support.ConceptAssignmentLoader
import com.soma.wes.retouch.service.RetouchService
import com.soma.wes.retouch.service.RetouchRequestService
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
    LambdaAiTaskProperties::class,
    MockGalleryProperties::class,
    GalleryInviteProperties::class,
    GalleryLifecycleProperties::class,
    CollabProperties::class,
)
@Import(
    ActivityRepository::class,
    ActivityRecorder::class,
    TimeConfig::class,
    SchedulingConfig::class,
    WebMvcConfig::class,
    GlobalExceptionHandler::class,
    CustomAuthenticationEntryPoint::class,
    CustomAccessDeniedHandler::class,
    SecureTokenGenerator::class,
    S3PhotoStorage::class,
    AwsLambdaConfig::class,
    LambdaAiTaskSender::class,
    GalleryInviteUrlResolver::class,
    CollabLinkResolver::class,
    GalleryAccessPolicy::class,
    GalleryService::class,
    PhotoService::class,
    PhotoPipelineRepository::class,
    PhotoSelectionService::class,
    CollabSessionQueryService::class,
    FolderService::class,
    CollabFolderReactionCleaner::class,
    RetouchService::class,
    PhotoViewAssembler::class,
    CollabPhotoViewAssembler::class,
    CollabPhotoMembership::class,
    RetouchRequestService::class,
    RetouchPhotoLoader::class,
    RetouchResultLoader::class,
    RetouchViewAssembler::class,
    ProductChildTrashRepository::class,
    ProductChildTrashService::class,
    AiFolderPlanner::class,
    // [REFACTOR-A 2026-09-27] FolderService·AiFolderMaterializer가 주입받는 FolderViewAssembler를 admin 컨텍스트에도 등록
    FolderViewAssembler::class,
    ConceptAssignmentLoader::class,
    DetailFolderAssignmentBulkRepository::class,
    // [REFACTOR-SUPPORT 2026-09-27] folder/service/AiFolderMaterializeService → folder/support/AiFolderMaterializer
    AiFolderMaterializer::class,
)
class AdminDomainDependenciesConfiguration {
    /**
     * BackOffice는 사용자 알림과 설정을 읽기 전용 운영 정보로만 노출한다. 관리자 mutation은
     * 관리자 감사/inbox에만 기록하며 제품 사용자 알림을 자동 생성하지 않는다.
     */
    @Bean
    fun userNotificationPublisher(): UserNotificationPublisher = UserNotificationPublisher { _, _, _, _, _, _ -> }
}
