package com.soma.wes.admin.resource

import com.soma.wes.admin.resource.support.AdminAlbumTemplateLayout
import com.soma.wes.admin.resource.support.InvalidAlbumTemplateLayoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdminAlbumTemplateLayoutTest {

    @Test
    fun `legacy 메타데이터 객체는 호환하고 구조화 슬롯은 sortOrder와 crop을 정규화한다`() {
        assertThat(AdminAlbumTemplateLayout.parse(mapOf("columns" to 2, "gutter" to 16)).folders).isEmpty()

        val parsed = AdminAlbumTemplateLayout.parse(
            mapOf(
                "folders" to listOf(
                    mapOf(
                        "name" to " 본식 ",
                        "items" to listOf(
                            mapOf("sortOrder" to 2),
                            mapOf(
                                "sortOrder" to 0,
                                "crop" to mapOf("x" to 0, "y" to 0.25, "width" to 1, "height" to 0.5),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertThat(parsed.folders.single().name).isEqualTo("본식")
        assertThat(parsed.folders.single().slots.map { it.sortOrder }).containsExactly(0, 2)
        assertThat(parsed.folders.single().slots.first().crop)
            .containsEntry("x", 0.0)
            .containsEntry("height", 0.5)
    }

    @Test
    fun `애매하거나 프레임을 벗어난 구조화 템플릿은 거부한다`() {
        val invalidLayouts = listOf(
            mapOf("folders" to "not-an-array"),
            mapOf("folders" to emptyList<Any>()),
            mapOf("folders" to listOf(mapOf("name" to "", "items" to emptyList<Any>()))),
            mapOf(
                "folders" to listOf(
                    mapOf(
                        "name" to "본식",
                        "items" to listOf(
                            mapOf("crop" to mapOf("x" to 0.8, "y" to 0, "width" to 0.4, "height" to 1)),
                        ),
                    ),
                ),
            ),
        )

        invalidLayouts.forEach { layout ->
            assertThatThrownBy { AdminAlbumTemplateLayout.parse(layout) }
                .isInstanceOf(InvalidAlbumTemplateLayoutException::class.java)
        }
    }
}
