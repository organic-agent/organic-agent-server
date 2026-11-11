package com.soma.wes.album.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

@JsonIgnoreProperties(value = ["cursor", "cursorMode", "spread"])
@Schema(description = "펼침면 순서대로 저장하는 앨범 편집 문서")
data class AlbumLayoutDto(
    @field:Schema(description = "생성할 때 정하는 한 쪽의 규격")
    val page: Page,
    @field:Schema(description = "펼침면 순서와 각 펼침면 안의 프레임 순서를 유지한다")
    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val spreads: List<Spread>,
    @field:Schema(description = "인쇄하지 않는 편집용 안내선", example = "[]")
    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val guides: List<Guide> = emptyList(),
    @field:Schema(description = "프레임 ID별 사진 ID. 한 사진은 한 프레임에만 넣는다.", example = """{"fr1":1234}""")
    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val assign: Map<String, Long> = emptyMap(),
    @field:Schema(description = "프레임 ID별로 배치된 사진의 확대 배율과 위치를 저장한다", example = """{"fr1":{"pid":1234,"s":1.4,"px":0.3,"py":0.5}}""")
    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val crop: Map<String, Crop> = emptyMap(),
    @field:Schema(description = "선택에서 빠져 비워진 프레임과 기존 사진 ID. 서버가 계산한다.", example = "{}")
    @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
    val gone: Map<String, Long> = emptyMap(),
) {
    data class Page(
        @field:Schema(description = "한 쪽의 너비(mm)", example = "305")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val w: Double,
        @field:Schema(description = "한 쪽의 높이(mm)", example = "305")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val h: Double,
        @field:Schema(description = "업계 규격명. 직접 입력한 크기는 null이다.", example = "12 × 12")
        val name: String? = null,
    )

    data class Spread(
        val id: String,
        @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
        val frames: List<Frame>,
        @field:Schema(description = "기본 틀의 장식 문구. 장식이 없으면 null이다.")
        val deco: Decoration? = null,
    )

    data class Frame(
        val id: String,
        @field:Schema(description = "왼쪽 0, 오른쪽 1. 두 쪽을 가로지르면 0이다.", example = "0")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val p: Int,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val x: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val y: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val w: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val h: Double,
    )

    data class Decoration(
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val p: Int,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val x: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val y: Double,
        @JsonSetter(nulls = Nulls.FAIL, contentNulls = Nulls.FAIL)
        val lines: List<String>,
    )

    data class Guide(
        val id: String,
        @field:Schema(description = "가로 안내선 h 또는 세로 안내선 v", example = "v")
        val dir: String,
        @field:Schema(description = "펼침면 기준 위치. 가로선은 0~1, 세로선은 0~2다.", example = "1.5")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val pos: Double,
        @field:Schema(description = "전체 펼침면이면 all, 특정 펼침면이면 그 ID다.", example = "all")
        val scope: String,
    )

    data class Crop(
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val pid: Long,
        @field:Schema(description = "프레임을 채우는 최소 크기 대비 확대 배율(1~4)", example = "1.4")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val s: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val px: Double,
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        val py: Double,
    )
}
