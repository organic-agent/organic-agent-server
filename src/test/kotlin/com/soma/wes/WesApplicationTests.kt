package com.soma.wes

import com.soma.wes.support.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WesApplicationTests {

    @Test
    fun contextLoads() {
    }

}
