package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolder
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoFolderRepository : JpaRepository<PhotoFolder, Long> {

    /** 자식폴더는 만든 순서대로 보여준다. 클러스터 고정 시의 묶음 순서가 그대로 유지된다. */
    fun findAllByGroupIdOrderByIdAsc(groupId: Long): List<PhotoFolder>

    fun findAllByGroupIdInOrderByIdAsc(groupIds: Collection<Long>): List<PhotoFolder>

    /**
     * 부모를 함께 걸어 찾는다. 부모가 이미 (id, galleryId)로 확인된 뒤라, 자식은 그 부모에
     * 속하는지만 보면 된다 — 다른 부모의 자식 id를 넘기는 요청이 여기서 404가 된다.
     */
    fun findByIdAndGroupId(id: Long, groupId: Long): PhotoFolder?

    /**
     * 갤러리와 함께 찾는다. 협업 세션 시드처럼 부모를 모르는 호출자가 쓴다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolder?

    fun deleteAllByGroupId(groupId: Long)
}
