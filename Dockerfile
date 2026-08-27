FROM eclipse-temurin:21-jre

# 로그 타임스탬프와 LocalDateTime.now()가 이 값을 따른다.
ENV TZ=Asia/Seoul

# 호환용 기본 Dockerfile은 공개 API를 가리킨다. 운영 CD는 Dockerfile.api를 명시한다.
ARG JAR_FILE=wes-api/build/libs/wes-api-0.0.1-SNAPSHOT.jar

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 wes \
    && useradd --system --uid 10001 --gid wes --no-create-home --home-dir /nonexistent wes

COPY --chown=wes:wes ${JAR_FILE} app.jar

USER wes
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=4 \
  CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app.jar"]
