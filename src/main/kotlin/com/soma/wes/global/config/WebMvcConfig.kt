package com.soma.wes.global.config

import com.soma.wes.global.filter.HttpLoggingFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 요청이 컨트롤러에 닿기까지 끼어드는 것들(서블릿 필터, MVC 인터셉터)의 등록 순서를 한곳에서 본다.
 */
@Configuration
class WebMvcConfig : WebMvcConfigurer {

    /**
     * 시큐리티 체인(DelegatingFilterProxy, order -100)보다 앞에 둔다. 뒤에 두면 인증에서 거절된 요청은 이 필터에 닿지 못해 traceId도 RESPONSE 줄도 없이 401만 남는다.
     */
    @Bean
    fun httpLoggingFilter(): FilterRegistrationBean<HttpLoggingFilter> =
        FilterRegistrationBean(HttpLoggingFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
}
