plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.soma"
version = "0.0.1-SNAPSHOT"
description = "wes"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Spring Boot 4.x BOM은 Testcontainers 버전을 관리하지 않으므로 직접 임포트한다.
// Spring Cloud AWS 4.0.x가 Boot 4 계열 대응 라인이다(3.x는 Boot 3 전용).
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
    }
}

dependencies {

    // Web
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Security & OAuth
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Database
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // pgvector의 vector 타입을 FloatArray 필드로 매핑한다.
    implementation("org.hibernate.orm:hibernate-vector")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Aws
    implementation("io.awspring.cloud:spring-cloud-aws-starter-parameter-store")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    implementation("software.amazon.awssdk:lambda")

    // Kotlin & JSON
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // API Docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Mockito의 자바 API는 Kotlin의 non-null 파라미터에서 깨진다(any()가 null을 반환한다).
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Monitoring
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // TestContainer
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
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

// bootJar와 별개로 라이브러리용 `-plain.jar`가 함께 만들어진다. 이 프로젝트를 다른 모듈이
// 의존하지 않으므로 쓸 일이 없고, build/libs에 jar가 둘이면 Dockerfile의 COPY 글롭이 실패한다.
tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Gradle은 기본적으로 테스트의 표준 출력과 로그를 삼킨다. 필터 실행 순서처럼
    // "로그를 봐야만 알 수 있는" 문제를 디버깅할 때 콘솔에 그대로 나오게 한다.
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
