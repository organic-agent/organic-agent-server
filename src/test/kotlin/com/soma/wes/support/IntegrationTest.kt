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
 * [DatabaseCleaner]는 공개 API와 달리 선택적 component scan을 쓰는 관리자 API에서도
 * 반드시 필요하므로 명시적으로 import한다. 같은 클래스가 공개 API scan에도 잡히지만 Spring은
 * 동일 configuration class를 한 번만 등록한다. 도메인별 Fixture는 각 애플리케이션 scan 또는
 * 필요한 테스트의 별도 import에 맡긴다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, DatabaseCleaner::class)
@ExtendWith(DatabaseClearExtension::class)
annotation class IntegrationTest
