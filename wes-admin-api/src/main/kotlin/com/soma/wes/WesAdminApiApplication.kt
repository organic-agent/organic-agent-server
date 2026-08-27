package com.soma.wes

import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.folder.domain.PhotoFolderGroup
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
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
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import java.util.TimeZone

@SpringBootApplication(scanBasePackages = ["com.soma.wes.admin"])
@EntityScan(
    basePackageClasses = [
        AdminAccount::class,
        AdminAuditLog::class,
        Gallery::class,
        Studio::class,
        Photo::class,
        PhotoSelection::class,
        CollabSession::class,
        PhotoFolderGroup::class,
        RetouchRound::class,
    ],
)
@EnableJpaRepositories(
    basePackageClasses = [
        AdminAccountRepository::class,
        AdminAuditLogRepository::class,
        GalleryRepository::class,
        StudioRepository::class,
        PhotoRepository::class,
        PhotoSelectionRepository::class,
        CollabSessionRepository::class,
        PhotoFolderGroupRepository::class,
        RetouchRoundRepository::class,
    ],
)
class WesAdminApiApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<WesAdminApiApplication>(*args)
}
