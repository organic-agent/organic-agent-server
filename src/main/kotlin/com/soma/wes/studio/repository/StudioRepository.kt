package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean

    /** FK 자식 삽입의 KEY SHARE와도 충돌해야 하므로 Hibernate의 NO KEY UPDATE보다 강하게 잠근다. */
    @Query(value = "select * from studios where id = :studioId for update", nativeQuery = true)
    fun findByIdForUpdate(@Param("studioId") studioId: Long): Studio?
}
