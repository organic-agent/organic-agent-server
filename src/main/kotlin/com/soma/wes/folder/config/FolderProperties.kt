package com.soma.wes.folder.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** AI 폴더 물질화의 정책 값. */
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
)
