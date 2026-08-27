plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    // 1차 전환 릴리스에서 admin entity도 공개 artifact에 임시 포함한다.
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

description = "WES public API application"

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
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    named("main") {
        kotlin.srcDir("../src/main/kotlin")
        kotlin.include(
            "com/soma/wes/WesApplication.kt",
            "com/soma/wes/transition/**",
            // WES_PUBLIC_ADMIN_TRANSITION_BRIDGE_REMOVE_AFTER_BACKOFFICE_CUTOVER:
            // 별도 admin API와 BackOffice upstream 전환이 검증될 때까지만 구 endpoint를 유지한다.
            "com/soma/wes/admin/**",
            "com/soma/wes/**/controller/**",
            "com/soma/wes/security/config/SecurityConfig.kt",
            "com/soma/wes/security/filter/Admin*.kt",
        )

        resources.srcDir("../src/main/resources")
        resources.include("application.yml", "db/migration/**")
    }

    named("test") {
        kotlin.srcDir("../src/test/kotlin")
        kotlin.exclude(
            "com/soma/wes/admin/**",
            // 관리자 enum까지 함께 검사하는 교차 모듈 계약 테스트는 admin 모듈에서 실행한다.
            "com/soma/wes/global/exception/ErrorCodeFormatTest.kt",
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

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
