# 배포용 이미지. Render에는 Java 네이티브 런타임이 없어 Dockerfile로 빌드한다.
# 빌드와 실행을 나눠 최종 이미지에 JDK와 Gradle 캐시가 들어가지 않게 한다.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# 래퍼와 빌드 스크립트를 먼저 복사한다. 소스만 바뀐 배포에서는 의존성 내려받기 층을
# 그대로 재사용해 빌드 시간을 아낀다.
COPY gradlew ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
# 테스트는 DB와 Ollama가 있어야 돌아가므로 빌드 환경에서는 뺀다. 실행 가능한 jar만 만든다.
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app

# root로 돌리지 않는다.
RUN useradd -r -u 1001 app && chown app /app
USER app
COPY --from=build --chown=app /build/build/libs/*.jar app.jar

# 배포 프로필로 고정한다. 호스팅 대시보드에서 덮을 수 있다.
ENV SPRING_PROFILES_ACTIVE=prod
# 무료 인스턴스는 512MB / 0.1 CPU다. 힙은 컨테이너 메모리에 비례해 잡고,
# 코어가 사실상 하나라 병렬 GC보다 SerialGC가 가볍다.
# C1까지만 컴파일하면(TieredStopAtLevel=1) 기동이 빨라진다 — 이 앱의 무거운 일은
# Cloudflare와 Supabase가 하므로 JIT 최적화를 포기해도 손해가 작다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

# 실제 포트는 호스팅이 주입하는 PORT를 따른다(server.port 참고). 아래는 로컬 기본값.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
