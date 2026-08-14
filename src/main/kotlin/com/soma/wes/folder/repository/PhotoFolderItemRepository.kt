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

    /** 부모 스코프 중복 검사. 요청한 사진 중 이미 부모 아래 어딘가에 든 것을 돌려준다. */
    fun findAllByGroupIdAndPhotoIdIn(groupId: Long, photoIds: Collection<Long>): List<PhotoFolderItem>

    fun deleteByFolderIdAndPhotoId(folderId: Long, photoId: Long): Long

    /**
     * 자식폴더 삭제 경로에서 항목을 먼저 지운다. 상위 삭제는 DB cascade가 최종 안전망이다.
     *
     * 파생 삭제로 두면 Spring Data가 항목을 전부 조회한 뒤 한 건씩 지운다. 폴더 하나가
     * 수백 장일 수 있어 벌크 삭제로 못박는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.folderId = :folderId")
    fun deleteAllByFolderId(@Param("folderId") folderId: Long): Int

    /** 부모폴더 삭제 경로. 자식폴더들의 항목을 한 번에 지운다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PhotoFolderItem i WHERE i.groupId = :groupId")
    fun deleteAllByGroupId(@Param("groupId") groupId: Long): Int

    /**
     * 같은 부모의 다른 자식으로 사진을 옮긴다. 옮길 사진이 전부 출발지에 있는지는 호출자가
     * 부모 행을 잠근 채 먼저 확인한다 — 이 UPDATE 자체는 있는 행만 옮기고 수를 돌려줄 뿐이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE PhotoFolderItem i SET i.folderId = :targetFolderId " +
            "WHERE i.folderId = :sourceFolderId AND i.photoId IN :photoIds",
    )
    fun moveAll(
        @Param("sourceFolderId") sourceFolderId: Long,
        @Param("targetFolderId") targetFolderId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
    ): Int
}
