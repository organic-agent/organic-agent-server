package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoFolderItemRepository : JpaRepository<PhotoFolderItem, Long> {

    fun findAllByFolderId(folderId: Long): List<PhotoFolderItem>

    fun findAllByFolderIdIn(folderIds: Collection<Long>): List<PhotoFolderItem>

    fun countByFolderId(folderId: Long): Long

    fun deleteByFolderIdAndPhotoId(folderId: Long, photoId: Long): Long

    /**
     * 일반 폴더 삭제 경로에서 항목을 먼저 지운다. 상위 갤러리·스튜디오 삭제는 DB cascade가
     * 최종 안전망이지만, 이 경로는 삭제한 항목 수를 서비스가 확인할 수 있게 명시적으로 처리한다.
     *
     * 파생 삭제(`deleteAllByFolderId`)로 두면 Spring Data가 항목을 전부 조회한 뒤 한 건씩
     * 지운다. 폴더 하나가 수백 장일 수 있어 벌크 삭제로 못박는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.folderId = :folderId")
    fun deleteAllByFolderId(@Param("folderId") folderId: Long): Int
}
