package com.soma.wes.activity.infrastructure

import com.soma.wes.activity.dto.ActivityMarksDto
import com.soma.wes.activity.service.port.ActivityBuffer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** 앱 메모리 메모장. 앱이 죽으면 아직 쓰지 않은 시각(최대 한 주기)을 잃는다 — 정렬·"N분 전" 표시용 값이라 감수한다. */
@Component
@ConditionalOnProperty(prefix = "app.activity.buffer", name = ["enabled"], havingValue = "true")
class InMemoryActivityBuffer : ActivityBuffer {

    private val galleries = ConcurrentHashMap<Long, ZonedDateTime>()
    private val workspaces = ConcurrentHashMap<Long, ZonedDateTime>()

    override fun markGallery(galleryId: Long, at: ZonedDateTime) {
        galleries.merge(galleryId, at, ::latest)
    }

    override fun markWorkspace(workspaceId: Long, at: ZonedDateTime) {
        workspaces.merge(workspaceId, at, ::latest)
    }

    override fun drain(): ActivityMarksDto = ActivityMarksDto(
        galleries = galleries.drainAll(),
        workspaces = workspaces.drainAll(),
    )

    private fun latest(a: ZonedDateTime, b: ZonedDateTime): ZonedDateTime = if (a.isAfter(b)) a else b

    /** 키마다 꺼내며 지운다 — 꺼낸 뒤 같은 키에 들어온 시각은 지워지지 않고 다음 번에 나온다. */
    private fun ConcurrentHashMap<Long, ZonedDateTime>.drainAll(): Map<Long, ZonedDateTime> =
        keys.toList().mapNotNull { id -> remove(id)?.let { at -> id to at } }.toMap()
}
