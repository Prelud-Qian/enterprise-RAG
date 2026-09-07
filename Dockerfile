# 多阶段构建：Maven 编译 → JRE 运行镜像
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 先拷贝 pom 拉依赖，利用 Docker 层缓存加速后续构建
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/enterprise-rag-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
