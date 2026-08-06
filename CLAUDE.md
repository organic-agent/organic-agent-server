# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`wes` is a Spring Boot 4.1.0 + Kotlin 2.3.21 backend, part of the larger `organic-agent-server` project (this directory is the `wes` service/module). It targets JVM 21 via the Gradle Kotlin DSL toolchain. It serves a wedding photo-selection product: photographers create galleries and upload originals; invited couples pick from them.

The infrastructure counterpart is the sibling repo `../../organic-agent-infrastructure` (Terraform: VPC/ALB/EC2/RDS, the photo S3 bucket, and the embedding Lambda). Deployment topology and the `/wes/prod/*` parameter contract are documented in the local-only `docs/notes/` (gitignored).

## Commands

Use the Gradle wrapper (`./gradlew`), not a system-installed Gradle.

- Build: `./gradlew build`
- Run the app: `./gradlew bootRun`
- Run all tests: `./gradlew test`
- Run a single test class: `./gradlew test --tests "com.soma.wes.WesApplicationTests"`
- Run a single test method: `./gradlew test --tests "com.soma.wes.WesApplicationTests.contextLoads"`
- Clean build: `./gradlew clean build`

Tests need Docker (Testcontainers). The embedding Lambda in `embedder/` is a separate Python project with its own README — Gradle does not build it.

## Architecture notes

- Base package: `com.soma.wes`. Packages are by domain (`auth`, `user`, `studio`, `gallery`, `photo`, `embedding`) with `global`/`security` for cross-cutting concerns. Within a domain: `domain` / `repository` / `service` / `controller` (+ `controller/docs` for the OpenAPI-annotated interface the controller implements) / `dto` / `exception` / `config` / `infrastructure`. There is no top-level `infrastructure` package — external-system adapters live inside the domain that uses them.
- Web stack: `spring-boot-starter-webmvc` (servlet-based Spring MVC, not WebFlux).
- JSON: `jackson-module-kotlin` (via the `tools.jackson.module` coordinates used by Spring Boot 4.x) for idiomatic Kotlin data class (de)serialization.
- Configuration lives in `src/main/resources/application.yml`, which imports `config/application-{cloud,db,variable}.yml`. Secrets and infra-derived values come from AWS Parameter Store (`/wes/{local,prod}/`) at startup.
- **Flyway owns the schema** (`src/main/resources/db/migration`); `ddl-auto` is `validate` in every profile. This is not a style preference: `photos.embedding` is a pgvector `vector(768)` column, and `CREATE EXTENSION vector` has to run before any table, which `ddl-auto` cannot do. Adding an entity means writing a migration. Prod is baselined at V1 (it predates Flyway), so V1 runs only on empty databases.
- **Postgres needs pgvector.** Local (`docker-compose.local.yml`) and Testcontainers both use `pgvector/pgvector:pg16`, not stock `postgres`.
- **Image bytes never pass through this server.** The app issues presigned S3 URLs and the browser uploads directly; embeddings are computed by a Lambda invoked once per gallery (`InvocationType.EVENT`), never per photo. `embedder/` holds that Lambda (Python, DINOv2, container image on ECR) — same repo, separate deploy path from the app.
- `EMBEDDING_DIMENSION` exists in three places that must agree: `Photo.EMBEDDING_DIMENSION`, the `vector(n)` column in the migration, and the infra repo's `embedding_dimension` variable (the Lambda's `EMBED_DIM`).
- Error responses are always `{code, message}` from an `ErrorCode` enum; codes follow `{DOMAIN}_{HTTP_STATUS}_{N}`, enforced by `ErrorCodeFormatTest` (add new enums to its list).

## Layering conventions

These are enforced by review, not by tooling. Follow them in new code.

**Services own both DTOs.** A controller passes the request DTO straight through and the service returns the response DTO; the controller only wraps it in `ResponseEntity`. Two consequences to keep in mind:

- **Entities must not reach a controller.** `open-in-view` is `false`, so an entity returned past the service transaction is detached — the day someone adds a `@ManyToOne`, every controller that maps one throws `LazyInitializationException` at the mapping line rather than at the real cause. Entities crossing *service → service* (e.g. `GalleryAccessPolicy.requireManager` returning `Gallery`) is fine; that stays inside the transaction.
- **Never let a bean-validation annotation be the only enforcement of a rule.** `@field:NotBlank` and friends only run because the controller says `@Valid`; a service called from anywhere else gets no validation. Real invariants live in the domain or the service (`Studio.isValidGalleryUrl`, `PhotoService.ALLOWED_CONTENT_TYPES`, `maxBatchSize`).

**External systems are reached only through an adapter in the domain's own `infrastructure` package.** A service must not inject an SDK client (`S3Presigner`, `LambdaClient`, an HTTP client for a third-party API) or handle its exceptions. Instead:

- The domain declares a **port** — an interface in its own `service` package, written in domain vocabulary (`PhotoStorage.presignUpload`, `EmbeddingInvoker.invoke`), naming no vendor type in its signatures.
- `{domain}/infrastructure` holds the **adapter** that implements it, named after the technology: `photo/infrastructure/S3PhotoStorage`, `embedding/infrastructure/LambdaEmbeddingInvoker`. The `@Configuration` that builds the SDK client stays in the domain's `config` alongside its properties (`embedding/config/AwsLambdaConfig`) — move it to `global/config` only once a second domain needs the same client.
- The adapter belongs to the domain, so keep it *in* that domain. A top-level `infrastructure` package would collect every vendor class in one bucket and split each domain in two.
- Dependencies point inward. An adapter may import its own domain's `config` properties and throw an `ErrorCode` (`LambdaEmbeddingInvoker` turns `SdkException` into `PhotoErrorCode.EMBEDDING_INVOCATION_FAILED`); no `service`, `domain`, `controller`, `repository`, or `dto` class may import an SDK type.
- Vendor detail stops at the boundary, logging included: the adapter logs the function name and status code, the service logs the domain event.

The point is not swappability — nobody is replacing S3. It is that a vendor type in a constructor spreads: once a service holds an SDK client, its exceptions, its retries, and its test doubles all become the domain's problem. Only `infrastructure` (adapters) and `config` (client beans) may name an SDK type:

```
grep -rln "software.amazon.awssdk" src/main/kotlin | grep -vE "/(infrastructure|config)/"   # must stay empty
```

**Controllers return `ResponseEntity<T>` from a block body**, never an expression body and never `@ResponseStatus` — the status belongs in exactly one place. Non-default statuses read `ResponseEntity.status(status).body(result)`; 200 is `ResponseEntity.ok(result)`. OpenAPI annotations live on a `*ControllerDocs` interface the controller implements, so its signatures must change in lockstep.

**One DTO per file**, named after the class. The only exception is a nested class used solely by its enclosing DTO (`IssueUploadUrlsRequest.FileRequest`).

**Private helper placement:** put a private method directly below the method that calls it. If several methods call it, put it below the lowest-positioned caller. Do not sweep all private methods to the bottom of the class — the point is that a helper sits next to the code it serves.

**Never call a `@Transactional` method from inside the same class.** Spring's transaction support is proxy-based, so a self-invocation bypasses the proxy and the annotation is *silently* ignored — writes then fall outside a transaction and dirty-checking updates vanish with no error. If a method needs to call transactional work, either make the helper private and untransactional (it inherits the caller's transaction) or move the transactional part to a separate bean. `OAuthLoginService` → `OAuthLoginProcessor.process` exists for exactly this reason; `OAuthLoginServiceTest` guards it.
- Kotlin compiler flags of note (`build.gradle.kts`): `-Xjsr305=strict` (treats JSR-305 nullability annotations strictly) and `-Xannotation-default-target=param-property` (annotations on constructor properties apply to both the parameter and the property by default).
- Tests use JUnit 5 (`useJUnitPlatform()`) plus `kotlin-test-junit5` and Spring's `spring-boot-starter-webmvc-test`.
