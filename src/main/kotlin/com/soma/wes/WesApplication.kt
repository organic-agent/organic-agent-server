package com.soma.wes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WesApplication

fun main(args: Array<String>) {
    runApplication<WesApplication>(*args)
}
