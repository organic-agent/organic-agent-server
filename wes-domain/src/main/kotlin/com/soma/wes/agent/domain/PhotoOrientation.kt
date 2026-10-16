package com.soma.wes.agent.domain

/** 가로·세로. 정사각형은 세로로 센다 — 앨범 지면에서 세로 칸에 들어가기 때문이다. */
enum class PhotoOrientation(val label: String) {
    LANDSCAPE("가로"),
    PORTRAIT("세로"),
    ;

    companion object {

        fun of(width: Int?, height: Int?): PhotoOrientation? {
            if (width == null || height == null) return null
            return if (width > height) LANDSCAPE else PORTRAIT
        }
    }
}
