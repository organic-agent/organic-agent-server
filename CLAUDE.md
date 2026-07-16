# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`wes` is a Spring Boot 4.1.0 + Kotlin 2.3.21 backend, part of the larger `organic-agent-server` project (this directory is the `wes` service/module). It targets JVM 21 via the Gradle Kotlin DSL toolchain and currently contains only the default Spring Initializr skeleton (`WesApplication.kt`, `application.yaml`, and a placeholder context-loads test) — there is no established package layout, REST endpoints, or persistence layer yet. When adding code, you are largely establishing the conventions rather than following existing ones.

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

- Build: `./gradlew build`
- Run the app: `./gradlew bootRun`
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.soma.wes.WesApplicationTests"`
- Run a single test method: `./gradlew test --tests "com.soma.wes.WesApplicationTests.contextLoads"`
- Clean build: `./gradlew clean build`

## Architecture notes

- Base package: `com.soma.wes`.
- Web stack: `spring-boot-starter-webmvc` (servlet-based Spring MVC, not WebFlux).
- JSON: `jackson-module-kotlin` (via the `tools.jackson.module` coordinates used by Spring Boot 4.x) for idiomatic Kotlin data class (de)serialization.
- Configuration lives in `src/main/resources/application.yaml` (YAML, not `.properties`).
- Kotlin compiler flags of note (`build.gradle.kts`): `-Xjsr305=strict` (treats JSR-305 nullability annotations strictly) and `-Xannotation-default-target=param-property` (annotations on constructor properties apply to both the parameter and the property by default).
- Tests use JUnit 5 (`useJUnitPlatform()`) plus `kotlin-test-junit5` and Spring's `spring-boot-starter-webmvc-test`.
