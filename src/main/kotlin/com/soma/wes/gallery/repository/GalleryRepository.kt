package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GalleryRepository : JpaRepository<Gallery, Long> {

    fun findAllByStudioId(studioId: Long): List<Gallery>

    fun findByStudioIdAndGalleryType(studioId: Long, galleryType: GalleryType): Gallery?

    /**
     * 갤러리 행을 잠그고 읽는다. 선택 앨범을 고칠 때 쓴다.
     *
     * 앨범 쪽 행이 아니라 갤러리 행을 잠그는 이유가 둘이다. 하나는 앨범이 아직 없을 수 있다는
     * 것 — "없으면 만든다"를 두 요청이 동시에 하면 유니크 제약에 걸린 한쪽이 통째로 실패하는데,
     * 없는 행은 잠글 수가 없다. 다른 하나는 목표 장수가 여기 있다는 것이다. 신랑과 신부가
     * 마지막 한 장을 동시에 담으면 둘 다 "아직 한 장 남았다"를 읽고 계약 장수를 넘긴다.
     * 갤러리가 이 갤러리의 선택에 대한 유일한 관문이라 여기를 잠그면 둘 다 막힌다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Gallery?

    @Query(value = "select * from galleries where studio_id = :studioId for update", nativeQuery = true)
    fun findAllByStudioIdForUpdate(@Param("studioId") studioId: Long): List<Gallery>
}
