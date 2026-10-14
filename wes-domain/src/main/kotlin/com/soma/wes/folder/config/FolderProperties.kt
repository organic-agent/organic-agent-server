package com.soma.wes.folder.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** 폴더 정책 값 — AI 폴더 물질화와 합치기 되돌리기. */
@ConfigurationProperties(prefix = "app.folder")
data class FolderProperties(
    /**
     * 이미 폴더가 있는 갤러리에 새 세부 폴더를 만드는 최소 장수. 기존 폴더에 들어가지 못한 새 사진이 이보다 적게 모이면
     * 폴더를 만들지 않고 미분류로 둔다 — 나눠 올릴 때마다 몇 장짜리 폴더가 늘지 않게 한다. 처음 분석(폴더에 든 사진이
     * 하나도 없을 때)에는 걸지 않는다.
     */
    val minNewDetailPhotos: Int = 5,
    /**
     * 새 사진뿐인 그룹을 "같은 세부 이름의 옛 사진이 든 폴더"로 합치려면, 그 세부 이름의 사진 중 옛 사진이 이 비율 이상이어야 한다.
     * 옛 사진 몇 장이 새 사진 수백 장을 끌고 가지 않게 한다.
     */
    val minOldShareForDetailMerge: Double = 0.1,
    /** 세부 폴더 합치기를 되돌릴 수 있는 시간. 웹은 알림이 떠 있는 몇 초만 쓰니 넉넉히 둔다. */
    val mergeUndoWindow: Duration = Duration.ofMinutes(3),
)
