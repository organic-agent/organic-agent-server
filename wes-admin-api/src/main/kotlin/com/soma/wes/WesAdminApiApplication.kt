package com.soma.wes

import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.analysis.domain.AnalysisJob
import com.soma.wes.analysis.repository.AnalysisJobRepository
import com.soma.wes.folder.domain.ConceptFolder
import com.soma.wes.folder.repository.DetailFolderRepository
import com.soma.wes.folder.repository.PhotoFolderAssignmentRepository
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.retouch.domain.RetouchRound
import com.soma.wes.retouch.repository.RetouchRoundRepository
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.util.TimeZone
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.soma.wes.admin"])
@EntityScan(
    basePackageClasses = [
        AdminAccount::class,
        AdminAuditLog::class,
        ConceptFolder::class,
        Gallery::class,
        Workspace::class,
        Studio::class,
        Photo::class,
        AnalysisJob::class,
        PhotoSelection::class,
        CollabSession::class,
        RetouchRound::class,
    ],
)
@EnableJpaRepositories(
    basePackageClasses = [
        AdminAccountRepository::class,
        AdminAuditLogRepository::class,
        DetailFolderRepository::class,
        PhotoFolderAssignmentRepository::class,
        GalleryRepository::class,
        WorkspaceRepository::class,
        WorkspaceMemberRepository::class,
        StudioRepository::class,
        PhotoRepository::class,
        AnalysisJobRepository::class,
        PhotoSelectionRepository::class,
        CollabSessionRepository::class,
        RetouchRoundRepository::class,
    ],
)
class WesAdminApiApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<WesAdminApiApplication>(*args)
}
