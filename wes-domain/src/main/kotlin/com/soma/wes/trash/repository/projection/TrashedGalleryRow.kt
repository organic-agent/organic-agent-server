package com.soma.wes.trash.repository.projection

import java.time.ZonedDateTime

/**
 * 휴지통 갤러리 한 줄. 엔티티가 아니라 읽기 전용 행이다 — 휴지통의 행은
 * `@SQLRestriction` 때문에 JPA 엔티티로는 조회할 수 없다.
 */
data class TrashedGalleryRow(
    val galleryId: Long,
    val title: String,
    val photoCount: Long,
    val deletedAt: ZonedDateTime,
)
