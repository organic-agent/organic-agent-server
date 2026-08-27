plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // 기존 admin 엔티티도 이 모듈에서 컴파일되므로 JPA no-arg/open 변환이 필요하다.
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "WES private admin API application"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
    }
}

dependencies {
    implementation(project(":wes-domain"))
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-flyway")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    named("main") {
        kotlin.srcDir("../src/main/kotlin")
        kotlin.include(
            "com/soma/wes/WesAdminApiApplication.kt",
            "com/soma/wes/admin/**",
            "com/soma/wes/security/filter/Admin*.kt",
        )
    }

    named("test") {
        kotlin.srcDir("../src/test/kotlin")
        kotlin.include(
            "com/soma/wes/admin/**",
            "com/soma/wes/support/**",
            "com/soma/wes/**/fixture/**",
            "com/soma/wes/global/exception/ErrorCodeFormatTest.kt",
            "com/soma/wes/trash/RecordingTrashPhotoStorage.kt",
            "com/soma/wes/trash/RecordingTrashPhotoStorageConfig.kt",
        )
        resources.srcDir("../src/test/resources")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.jar {
    enabled = false
}

// 운영 admin artifact에는 Flyway와 migration을 넣지 않되, 통합 테스트는 공개 API와 같은
// migration 전체를 깨끗한 PostgreSQL에 적용해 schema 계약을 검증한다.
tasks.processTestResources {
    from("../src/main/resources/db/migration") {
        into("db/migration")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
