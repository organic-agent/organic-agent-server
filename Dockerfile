FROM eclipse-temurin:21-jre

# 로그 타임스탬프와 LocalDateTime.now()가 이 값을 따른다.
ENV TZ=Asia/Seoul

ARG JAR_FILE=build/libs/wes-0.0.1-SNAPSHOT.jar

COPY ${JAR_FILE} app.jar

ENTRYPOINT ["java", "-jar", "/app.jar"]
