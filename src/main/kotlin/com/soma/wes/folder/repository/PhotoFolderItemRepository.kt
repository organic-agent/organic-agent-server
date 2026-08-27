package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoFolderItemRepository : JpaRepository<PhotoFolderItem, Long> {

    fun findAllByFolderId(folderId: Long): List<PhotoFolderItem>

    fun findAllByFolderIdOrderBySortOrderAscIdAsc(folderId: Long): List<PhotoFolderItem>

    fun findAllByFolderIdIn(folderIds: Collection<Long>): List<PhotoFolderItem>

    fun findAllByFolderIdInOrderBySortOrderAscIdAsc(folderIds: Collection<Long>): List<PhotoFolderItem>

    fun countByFolderId(folderId: Long): Long

    /** 부모 스코프 중복 검사. 요청한 사진 중 이미 부모 아래 어딘가에 든 것을 돌려준다. */
    fun findAllByGroupIdAndPhotoIdIn(groupId: Long, photoIds: Collection<Long>): List<PhotoFolderItem>

    fun deleteByFolderIdAndPhotoId(folderId: Long, photoId: Long): Long

    /**
     * 자식폴더 삭제 경로에서 항목을 먼저 지운다. 상위 삭제는 DB cascade가 최종 안전망이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.folderId = :folderId")
    fun deleteAllByFolderId(@Param("folderId") folderId: Long): Int

    /** 부모폴더 삭제 경로. 자식폴더들의 항목을 한 번에 지운다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.groupId = :groupId")
    fun deleteAllByGroupId(@Param("groupId") groupId: Long): Int

    /** Int 상한에 닿은 legacy/관리자 배치를 현재 표시 순서대로 재번호화할 때 쓴다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE PhotoFolderItem i SET i.sortOrder = :sortOrder, i.version = i.version + 1 " +
            "WHERE i.id = :itemId",
    )
    fun updateSortOrder(
        @Param("itemId") itemId: Long,
        @Param("sortOrder") sortOrder: Int,
    ): Int

    /** 부모 행을 잠근 호출자가 한 장씩 도착 폴더의 마지막 순서 뒤에 붙인다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE PhotoFolderItem i SET i.folderId = :targetFolderId, i.sortOrder = :sortOrder, " +
            "i.version = i.version + 1 " +
            "WHERE i.folderId = :sourceFolderId AND i.photoId = :photoId",
    )
    fun moveOne(
        @Param("sourceFolderId") sourceFolderId: Long,
        @Param("targetFolderId") targetFolderId: Long,
        @Param("photoId") photoId: Long,
        @Param("sortOrder") sortOrder: Int,
    ): Int
}
