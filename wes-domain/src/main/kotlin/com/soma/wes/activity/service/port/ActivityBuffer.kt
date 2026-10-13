package com.soma.wes.activity.service.port

import com.soma.wes.activity.dto.ActivityMarksDto
import java.time.ZonedDateTime

/**
 * 활동 시각을 DB에 쓰기 전에 모아 두는 메모장(#286 (f)). 같은 갤러리·작업공간은 가장 늦은 시각 하나만 남긴다.
 *
 * DB 쓰기가 `greatest()`라 서버가 여러 대여도 각자 모아 써도 결과가 같다 — 공유 저장소가 정확성을 위해 필요하지는 않다.
 * 서버가 늘어 종료 때 잃는 양이 문제가 되면 Redis 어댑터를 더해 설정으로 바꾼다.
 */
interface ActivityBuffer {
    fun markGallery(galleryId: Long, at: ZonedDateTime)

    fun markWorkspace(workspaceId: Long, at: ZonedDateTime)

    /** 모인 시각을 꺼내고 비운다. 꺼내는 동안 들어온 시각은 다음 번에 나온다. */
    fun drain(): ActivityMarksDto
}
