package com.soma.wes.admin.resource.support

/**
 * 앨범 템플릿의 실행 가능한 최소 계약.
 *
 * 기존 템플릿은 `columns`, `gutter`처럼 화면 메타데이터만 가진 자유 형식 객체였으므로
 * `folders`가 없는 객체는 그대로 유효하다. `folders`를 명시한 경우에만 아래 슬롯 계약을
 * 엄격히 검증해, 자동 목업이 애매한 값을 추측하지 않게 한다.
 *
 * ```json
 * {
 *   "folders": [
 *     {
 *       "name": "본식",
 *       "items": [
 *         { "sortOrder": 0, "crop": { "x": 0, "y": 0, "width": 1, "height": 1 } }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * `items`는 사진 id를 담지 않는 배치 슬롯이다. 제출 리비전의 새 사진을 슬롯 순서대로
 * 채우며, 이미 수동 배치된 사진의 폴더·상대 순서·crop은 실행기가 우선 보존한다.
 */
object AdminAlbumTemplateLayout {
    private const val MAX_FOLDERS = 50
    private const val MAX_SLOTS = 1_000
    private const val MAX_FOLDER_NAME_LENGTH = 100
    private const val EPSILON = 0.000_001

    fun parse(layout: Map<String, Any?>): Layout {
        if (!layout.containsKey("folders")) return Layout(emptyList())
        val rawFolders = layout["folders"] as? Collection<*>
            ?: throw InvalidAlbumTemplateLayoutException()
        if (rawFolders.isEmpty() || rawFolders.size > MAX_FOLDERS) {
            throw InvalidAlbumTemplateLayoutException()
        }

        val names = mutableSetOf<String>()
        var slotCount = 0
        val folders = rawFolders.map { rawFolder ->
            val folder = rawFolder as? Map<*, *> ?: throw InvalidAlbumTemplateLayoutException()
            val name = folder["name"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_FOLDER_NAME_LENGTH }
                ?: throw InvalidAlbumTemplateLayoutException()
            if (!names.add(name)) throw InvalidAlbumTemplateLayoutException()

            val rawItems = when (val value = folder["items"]) {
                null -> emptyList<Any?>()
                is Collection<*> -> value.toList()
                else -> throw InvalidAlbumTemplateLayoutException()
            }
            slotCount += rawItems.size
            if (slotCount > MAX_SLOTS) throw InvalidAlbumTemplateLayoutException()

            val sortOrders = mutableSetOf<Int>()
            val slots = rawItems.mapIndexed { index, rawItem ->
                val item = rawItem as? Map<*, *> ?: throw InvalidAlbumTemplateLayoutException()
                val sortOrder = when (val value = item["sortOrder"]) {
                    null -> index
                    is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
                    else -> value.toString().toIntOrNull()
                }?.takeIf { it >= 0 } ?: throw InvalidAlbumTemplateLayoutException()
                if (!sortOrders.add(sortOrder)) throw InvalidAlbumTemplateLayoutException()
                Slot(sortOrder, crop(item["crop"]))
            }.sortedBy(Slot::sortOrder)
            Folder(name, slots)
        }
        return Layout(folders)
    }

    private fun crop(value: Any?): Map<String, Any?>? {
        if (value == null) return null
        val raw = value as? Map<*, *> ?: throw InvalidAlbumTemplateLayoutException()
        val x = raw.number("x")
        val y = raw.number("y")
        val width = raw.number("width")
        val height = raw.number("height")
        if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x + width > 1 + EPSILON || y + height > 1 + EPSILON
        ) {
            throw InvalidAlbumTemplateLayoutException()
        }
        return linkedMapOf("x" to x, "y" to y, "width" to width, "height" to height)
    }

    private fun Map<*, *>.number(name: String): Double {
        val number = when (val value = this[name]) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        } ?: throw InvalidAlbumTemplateLayoutException()
        if (!number.isFinite()) throw InvalidAlbumTemplateLayoutException()
        return number
    }

    data class Layout(val folders: List<Folder>)
    data class Folder(val name: String, val slots: List<Slot>)
    data class Slot(val sortOrder: Int, val crop: Map<String, Any?>?)
}

class InvalidAlbumTemplateLayoutException : RuntimeException("INVALID_ALBUM_TEMPLATE_LAYOUT")
