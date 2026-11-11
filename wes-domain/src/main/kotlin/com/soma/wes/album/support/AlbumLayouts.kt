package com.soma.wes.album.support

import com.soma.wes.album.dto.AlbumLayoutDto
import com.soma.wes.album.exception.AlbumErrorCode
import com.soma.wes.album.exception.AlbumException

internal object AlbumLayouts {
    /** 편집기에서 허용하는 한 쪽 크기(mm)와 펼침면 수(최대 100쪽). */
    private val pageSize = 100.0..500.0
    private val spreadCount = 1..50
    /** 편집기가 소수점 아래 4자리로 반올림한 시작점과 너비·높이의 합에 적용한다. */
    private const val FRAME_EDGE_TOLERANCE = 0.0001
    private val unit = 0.0..1.0
    private val cropScale = 1.0..4.0
    private val idPattern = Regex("[A-Za-z0-9_-]{1,100}")
    private val reservedIds = setOf("__proto__", "constructor", "prototype")

    fun validate(layout: AlbumLayoutDto, selected: Set<Long>) {
        if (layout.page.w !in pageSize || layout.page.h !in pageSize ||
            layout.page.name?.length?.let { it > 100 } == true || layout.spreads.size !in spreadCount
        ) throw AlbumException(AlbumErrorCode.INVALID_LAYOUT)

        val spreads = layout.spreads.map { it.id }
        val spreadIds = spreads.toSet()
        val frames = layout.spreads.flatMap { it.frames }
        val frameIds = frames.map { it.id }
        if (!validIds(spreads) || "all" in spreads || !validIds(frameIds) ||
            !validIds(layout.guides.map { it.id }) || frames.any { !validFrame(it) } ||
            layout.spreads.any { it.deco?.let { decoration -> !validDecoration(decoration) } == true } ||
            layout.guides.any { !validGuide(it, spreadIds) }
        ) throw AlbumException(AlbumErrorCode.INVALID_LAYOUT)
        if (frames.size > selected.size) throw AlbumException(AlbumErrorCode.TOO_MANY_FRAMES)

        val assignedPhotos = layout.assign.values.toSet()
        if (!frameIds.toSet().containsAll(layout.assign.keys) || !selected.containsAll(assignedPhotos) ||
            assignedPhotos.size != layout.assign.size ||
            layout.crop.any { (id, crop) ->
                layout.assign[id] != crop.pid || crop.s !in cropScale || crop.px !in unit || crop.py !in unit
            }
        ) throw AlbumException(AlbumErrorCode.INVALID_ASSIGNMENT)
    }

    private fun validIds(ids: List<String>): Boolean =
        ids.size == ids.toSet().size && ids.all { idPattern.matches(it) && it !in reservedIds }

    private fun validFrame(frame: AlbumLayoutDto.Frame): Boolean =
        frame.p in 0..1 && frame.x in unit && frame.y in unit && frame.w > 0 && frame.h > 0 &&
            frame.p + frame.x + frame.w <= 2.0 + FRAME_EDGE_TOLERANCE &&
            frame.y + frame.h <= 1.0 + FRAME_EDGE_TOLERANCE

    private fun validDecoration(deco: AlbumLayoutDto.Decoration): Boolean =
        deco.p in 0..1 && deco.x in unit && deco.y in unit

    private fun validGuide(guide: AlbumLayoutDto.Guide, spreadIds: Set<String>): Boolean =
        (guide.scope == "all" || guide.scope in spreadIds) &&
            when (guide.dir) {
                "h" -> guide.pos in unit
                "v" -> guide.pos in 0.0..2.0
                else -> false
            }

    fun requireComplete(layout: AlbumLayoutDto, selected: Set<Long>) {
        val frameCount = layout.spreads.sumOf { it.frames.size }
        if (selected.isEmpty() || frameCount != selected.size || layout.assign.values.toSet() != selected ||
            layout.assign.size != frameCount
        ) throw AlbumException(AlbumErrorCode.INCOMPLETE)
    }

    /** 선택에서 빠진 사진은 빈 프레임의 이력으로만 남긴다. 서명 URL은 문서에 저장하지 않는다. */
    fun reconcile(layout: AlbumLayoutDto, selected: Set<Long>): AlbumLayoutDto {
        val removed = layout.assign.filterValues { it !in selected }
        if (removed.isEmpty()) return layout

        return layout.copy(
            assign = layout.assign - removed.keys,
            crop = layout.crop - removed.keys,
            gone = layout.gone + removed,
        )
    }

    /** 이전에 배치했던 사진만 gone에 남겨 다른 갤러리 사진 ID나 임의 표식이 저장되는 것을 막는다. */
    fun preserveRemoved(
        incoming: AlbumLayoutDto,
        previous: AlbumLayoutDto?,
        selected: Set<Long>,
    ): AlbumLayoutDto {
        val emptyFrames = incoming.spreads.flatMap { it.frames }.map { it.id }.toSet() - incoming.assign.keys
        val gone = previous?.let { reconcile(it, selected).gone }.orEmpty()
        return incoming.copy(gone = gone.filterKeys { it in emptyFrames })
    }

    fun requireSameLayout(sent: AlbumLayoutDto, client: AlbumLayoutDto) {
        if (sent.page != client.page || sent.spreads != client.spreads || sent.guides != client.guides) {
            throw AlbumException(AlbumErrorCode.CLIENT_LAYOUT_LOCKED)
        }
    }

    fun changedFrames(sent: AlbumLayoutDto, returned: AlbumLayoutDto): List<String> {
        val frameIds = sent.spreads.flatMap { it.frames }.map { it.id }
        return frameIds.filter { sent.assign[it] != returned.assign[it] || sent.crop[it] != returned.crop[it] }
    }
}
