package com.soma.wes.global.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 요청마다 traceId를 MDC에 심고 `[REQUEST]`·`[RESPONSE]` 한 줄씩을 남긴다.
 * `@Component`를 붙이지 않는다. 붙이면 Spring Boot가 서블릿 필터로 한 번 더 등록한다
 */
class HttpLoggingFilter : OncePerRequestFilter() {

    companion object {
        /** logback 패턴의 `%X{traceId}`와 같아야 한다. */
        const val TRACE_ID_KEY = "traceId"

        /** 인증 필터가 로그인 사용자 id(Long)를 실어 보내는 request attribute 이름. */
        const val USER_ID_ATTRIBUTE = "userId"

        /** UUID 32자 중 앞부분만 쓴다. 한 서버의 동시 요청을 구분하기엔 충분하고 로그 줄이 짧아진다. */
        private const val TRACE_ID_LENGTH = 16

        private const val MASK_VALUE = "****"

        /** 쿼리스트링에 실려 오면 값 대신 [MASK_VALUE]를 찍는 키. 대소문자를 가리지 않는다. */
        private val SENSITIVE_KEYS = setOf("token", "accesstoken", "refreshtoken", "password", "authorization", "apikey")

        private val PATH_PATTERN_PARSER = PathPatternParser()

        /** 헬스체크·문서 조회는 양이 많고 알려주는 것이 없다. traceId는 심되 줄은 남기지 않는다. */
        private val EXCLUDE_PATTERNS: List<PathPattern> = listOf(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
        ).map(PATH_PATTERN_PARSER::parse)
    }

    private val log = LoggerFactory.getLogger(HttpLoggingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        MDC.put(TRACE_ID_KEY, generateTraceId())

        if (isExcluded(request)) {
            try {
                filterChain.doFilter(request, response)
            } finally {
                MDC.clear()
            }
            return
        }

        logRequest(request)

        try {
            filterChain.doFilter(request, response)
            logResponse(request, response)
        } finally {
            MDC.clear()
        }
    }

    private fun isExcluded(request: HttpServletRequest): Boolean {
        val path = PathContainer.parsePath(request.requestURI)
        return EXCLUDE_PATTERNS.any { it.matches(path) }
    }

    private fun generateTraceId(): String =
        UUID.randomUUID().toString().replace("-", "").take(TRACE_ID_LENGTH)

    private fun logRequest(request: HttpServletRequest) {
        log.info("[REQUEST] {} {}", request.method, decodedRequestUri(request))
    }

    private fun logResponse(request: HttpServletRequest, response: HttpServletResponse) {
        val userId = request.getAttribute(USER_ID_ATTRIBUTE) as? Long
        val status = HttpStatus.resolve(response.status) ?: response.status

        log.info("[RESPONSE] {} userId={} ({})", decodedRequestUri(request), userId, status)
    }

    private fun decodedRequestUri(request: HttpServletRequest): String {
        val path = request.requestURI
        val query = request.queryString?.takeIf { it.isNotBlank() } ?: return path

        return "$path?${maskSensitiveParams(decodeQuery(query))}"
    }

    private fun decodeQuery(rawQuery: String): String =
        try {
            URLDecoder.decode(rawQuery, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            log.warn("쿼리 디코딩 실패 query={}, msg={}", rawQuery, e.message)
            rawQuery
        }

    private fun maskSensitiveParams(decodedQuery: String): String =
        decodedQuery.split("&").joinToString("&") { param ->
            val key = param.substringBefore("=", missingDelimiterValue = "")
            if (key.lowercase() in SENSITIVE_KEYS) "$key=$MASK_VALUE" else param
        }
}
