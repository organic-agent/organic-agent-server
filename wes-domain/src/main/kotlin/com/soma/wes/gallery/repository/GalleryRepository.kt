package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface GalleryRepository : JpaRepository<Gallery, Long> {

    fun findAllByWorkspaceId(workspaceId: Long): List<Gallery>

    fun findAllByWorkspaceIdIn(workspaceIds: Collection<Long>): List<Gallery>

    /** 갤러리 상태·마감·정원을 보고 쓰는 쓰기(설정·상태 전이, 멤버·초대, 업로드 한도, 선택 담기·빼기·제출·내보내기, 폴더 편집·AI 폴더 물질화, 보정 회차, 댓글·협업 세션)가 잠근다 — 확인한 갤러리 상태가 쓰기 전에 바뀌지 않게(예: 제출 뒤 대기하던 폴더 이동, 동시 업로드가 함께 한도를 넘김). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Gallery?
}

fun GalleryRepository.requireById(id: Long): Gallery =
    findById(id)
        .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }

fun GalleryRepository.requireWithLockById(id: Long): Gallery =
    findWithLockById(id)
        ?: throw GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)
