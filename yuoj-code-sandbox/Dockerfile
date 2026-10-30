ARG JAVA_BASE_IMAGE=eclipse-temurin:8-jdk-jammy@sha256:185d4ef919b98cd195690c33f3fd93bd49c66c70f0b662e54566c0dbaebea6ce
FROM ${JAVA_BASE_IMAGE}

WORKDIR /app
ENV TZ=Asia/Shanghai

# Compilation currently runs inside the sandbox service environment.
RUN apt-get update \
    && apt-get install -y --no-install-recommends g++ libc6-dev \
    && rm -rf /var/lib/apt/lists/*

COPY target/yuoj-code-sandbox-0.0.1-SNAPSHOT.jar /app/app.jar
RUN mkdir -p /app/tmpCode

EXPOSE 8090

# Select the Spring profile at runtime, e.g. SPRING_PROFILES_ACTIVE=prod.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
