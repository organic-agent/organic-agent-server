package com.soma.wes.support

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * 통합 테스트의 단일 진입점. `@SpringBootTest` + MockMvc + pgvector Testcontainers +
 * 테스트 간 TRUNCATE 정리를 한 번에 조립한다.
 *
 * 모든 통합 테스트가 **같은 조합**을 쓰는 것이 핵심이다 — 조합이 같아야 Spring 컨텍스트
 * 하나를 전원이 공유한다. 개별 테스트에 `@TestPropertySource`나 목 빈을 붙이면 그 파일만
 * 새 컨텍스트를 띄워 전체 실행이 그만큼 느려진다. 공용 페이크가 필요하면 여기 `@Import`
 * 목록에 추가해 전원이 공유하게 하라.
 *
 * [DatabaseCleaner]와 도메인별 Fixture(`{domain}/fixture`)는 `@Import`에 없다 —
 * `com.soma.wes` 아래의 `@Component`라 스캔으로 이미 모든 컨텍스트에 등록되고, 여기 다시
 * 적으면 아직 이 애노테이션을 쓰지 않는 `@SpringBootTest` + `@Import(TestcontainersConfiguration)`
 * 테스트들과 설정이 달라져 컨텍스트가 둘로 갈라진다. 같은 이유로 픽스처에 `@TestComponent`를
 * 쓰지 않는다 — 스캔에서 제외되어 결국 이 `@Import` 목록으로 돌아오게 된다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(DatabaseClearExtension::class)
annotation class IntegrationTest
