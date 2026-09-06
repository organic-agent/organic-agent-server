package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.Gallery
import java.time.ZonedDateTime
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

interface GalleryLifecycleRepository : Repository<Gallery, Long> {
    @Query("""
        select g.id from Gallery g join Workspace w on w.id = g.workspaceId
        where g.id > :afterId and g.stage <> com.soma.wes.gallery.domain.GalleryStage.ARCHIVED
          and (g.selectionDeadline between :now and :soon
               or (w.type = com.soma.wes.workspace.domain.WorkspaceType.PERSONAL and g.planExpiresAt <= :soon))
        order by g.id
    """)
    fun findCandidates(afterId: Long, now: ZonedDateTime, soon: ZonedDateTime, pageable: Pageable): List<Long>
}
