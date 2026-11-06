package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface StudioRepository : JpaRepository<Studio, Long> {

    fun existsByIdAndSuspendedAtIsNull(id: Long): Boolean

    fun findAllByIdInAndSuspendedAtIsNull(ids: Collection<Long>): List<Studio>

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean

    /** 스튜디오 정보 수정(`StudioService.update`·`updateMyStudio`)이 잠근다 — 같은 스튜디오의 주소 중복 확인과 갱신을 한 요청씩 하게. 인자는 이름과 달리 workspace id다([Studio.userId]). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUserId(userId: Long): Studio?
}
