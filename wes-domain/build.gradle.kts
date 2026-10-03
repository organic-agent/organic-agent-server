plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("io.spring.dependency-management")
}

description = "WES shared entities, policies, services, and infrastructure"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// 실행 앱이 아닌 공통 라이브러리다. API 모듈들이 쓰는 프레임워크 타입도 함께 노출한다.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
    }
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-client")
    api("org.bouncycastle:bcprov-jdk18on:1.85")

    api("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.hibernate.orm:hibernate-vector")

    api("org.springframework.boot:spring-boot-starter-validation")
    api("io.awspring.cloud:spring-cloud-aws-starter-parameter-store")
    api("io.awspring.cloud:spring-cloud-aws-starter-s3")
    api("software.amazon.awssdk:lambda")
    // GPU score 워커 EC2 켜기·끄기(analysis 도메인의 Ec2ScoreWorkerPool).
    api("software.amazon.awssdk:ec2")
    api("software.amazon.awssdk:bedrockruntime")
    // Bedrock 클라이언트의 소켓 타임아웃을 직접 잡기 위해 컴파일 의존으로 올린다(원래는 런타임 전이).
    api("software.amazon.awssdk:apache-client")

    api("org.apache.pdfbox:pdfbox:3.0.8")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    api("org.springframework.boot:spring-boot-starter-actuator")
}

sourceSets {
    named("main") {
        kotlin.srcDir("../src/main/kotlin")
        kotlin.exclude(
            "com/soma/wes/WesApplication.kt",
            "com/soma/wes/admin/**",
            "com/soma/wes/**/controller/**",
            "com/soma/wes/security/config/SecurityConfig.kt",
            "com/soma/wes/security/filter/Admin*.kt",
        )

        resources.srcDir("../src/main/resources")
        resources.include("config/**", "logback-spring.xml", "fonts/retouch/**")
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
