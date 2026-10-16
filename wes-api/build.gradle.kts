plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
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

    testImplementation(testFixtures(project(":wes-domain")))
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

// 관리자 통합 테스트가 운영과 같은 SQL을 사용하도록 마이그레이션 원본만 제공한다.
val migrationResourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("migration-resources")
    from(layout.projectDirectory.dir("src/main/resources")) {
        include("db/migration/**")
    }
}

configurations.create("migrationResources") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(migrationResourcesJar)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
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
