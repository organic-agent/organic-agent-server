package com.soma.wes.global.exception

import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.cluster.exception.ClusterErrorCode
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.security.exception.AuthorizationErrorCode
import com.soma.wes.selection.exception.SelectionErrorCode
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.user.exception.UserErrorCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ErrorCode.code]가 `{도메인}_{HTTP상태}_{일련번호}` 규칙을 지키는지 강제한다.
 *
 * 규칙이 문서에만 있으면 어긋난 코드가 조용히 섞인다. 실제로 404인 에러가 `GLOBAL_4004`(=400의 4번으로 읽힌다)로
 * 들어와 있었고, 코드만 봐서는 클라이언트도 우리도 알아챌 수 없었다.
 *
 * 새 도메인의 enum을 만들면 [allErrorCodes]에 추가한다.
 */
class ErrorCodeFormatTest {

    private val allErrorCodes: List<ErrorCode> =
        GlobalErrorCode.entries + AuthErrorCode.entries + AuthorizationErrorCode.entries + UserErrorCode.entries +
            StudioErrorCode.entries + GalleryErrorCode.entries + PhotoErrorCode.entries +
            ClusterErrorCode.entries + FolderErrorCode.entries + SelectionErrorCode.entries

    private val format = Regex("""^([A-Z]+)_(\d{3})_(\d+)$""")

    @Test
    fun `코드는 도메인_상태_일련번호 형식이다`() {
        allErrorCodes.forEach { errorCode ->
            assertTrue(
                format.matches(errorCode.code),
                "${errorCode.code}는 {도메인}_{HTTP상태}_{일련번호} 형식이 아니다(예: AUTH_401_2).",
            )
        }
    }

    @Test
    fun `코드의 상태 부분은 실제 응답 상태와 같다`() {
        allErrorCodes.forEach { errorCode ->
            val statusInCode = format.find(errorCode.code)?.groupValues?.get(2)?.toInt()
            assertEquals(
                errorCode.httpStatus.value(),
                statusInCode,
                "${errorCode.code}는 ${errorCode.httpStatus.value()}로 응답하면서 코드에는 다른 상태를 적고 있다.",
            )
        }
    }

    @Test
    fun `코드는 서로 겹치지 않는다`() {
        // 클라이언트는 code 하나로 분기한다. 두 원인이 같은 코드를 쓰면 분기할 방법이 없다.
        val duplicates = allErrorCodes.groupBy { it.code }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "중복된 에러 코드: $duplicates")
    }
}
