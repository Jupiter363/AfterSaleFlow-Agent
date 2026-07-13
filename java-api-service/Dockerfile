# 文件作用：容器镜像构建文件，定义服务运行环境、依赖安装和启动入口。
# 说明：本注释用于帮助读者先了解文件用途，再修改具体配置。

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu@sha256:f89b256658018e9a591f64b396a1dd82978af20b9a52605c0185a77c95a10eb0 AS build
WORKDIR /workspace

COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw -s .mvn/settings.xml -B -ntp dependency:go-offline

COPY src ./src
RUN ./mvnw -s .mvn/settings.xml -B -ntp clean package -DskipTests

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu@sha256:f89b256658018e9a591f64b396a1dd82978af20b9a52605c0185a77c95a10eb0
WORKDIR /home/app

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=app:app /workspace/target/java-api-service-*.jar /home/app/app.jar

USER app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
    CMD curl --fail --silent http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/home/app/app.jar"]
