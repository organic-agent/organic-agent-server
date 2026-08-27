package com.soma.wes

import com.soma.wes.transition.PublicAdminTransitionBridge
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@PublicAdminTransitionBridge
@SpringBootApplication
@ConfigurationPropertiesScan
class WesApplication

fun main(args: Array<String>) {
    // 배포 환경(컨테이너 TZ)에 좌우되지 않도록 JVM 기본 시간대를 KST로 못박는다.
    // Spring 부트스트랩보다 먼저 실행되어야 하므로 runApplication 이전에 둔다.
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    runApplication<WesApplication>(*args)
}
