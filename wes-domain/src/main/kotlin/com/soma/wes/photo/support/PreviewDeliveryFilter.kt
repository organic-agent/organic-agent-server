package com.soma.wes.photo.support

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 조회 요청의 `preview=signed`를 [PreviewDelivery]에 옮긴다(#336). 목록·상세·셀렉·추천 서비스 시그니처마다 인자를 늘리지 않고,
 * 보기 주소를 정하는 곳([PhotoViewAssembler]) 한 곳만 이 값을 본다.
 */
@Component
class PreviewDeliveryFilter(
    private val previewDelivery: PreviewDelivery,
) : OncePerRequestFilter() {

    companion object {
        private const val PARAM = "preview"
        private const val SIGNED = "signed"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        previewDelivery.forceSigned(request.getParameter(PARAM) == SIGNED)
        try {
            filterChain.doFilter(request, response)
        } finally {
            previewDelivery.clear()
        }
    }
}
