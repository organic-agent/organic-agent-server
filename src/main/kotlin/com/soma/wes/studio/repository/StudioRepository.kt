package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    /** 없는 Mock 갤러리를 동시에 만드는 요청들이 같은 부모 행 앞에서 직렬화되게 한다. */
    @Query(value = "select * from studios where user_id = :userId for update", nativeQuery = true)
    fun findByUserIdForUpdate(@Param("userId") userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean
}
