# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先只拷 pom，把依赖层缓存起来：改代码不会重新下载依赖
COPY pom.xml .
# 小内存构建机（如 Render 免费实例）安全阀：限制 Maven 的堆与元空间
ENV MAVEN_OPTS="-Xmx320m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8"
RUN mvn -B -ntp -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 时区：日志和 deadline 判断都要用国内时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build /app/target/*.jar app.jar

# 默认用 H2（容器内无需额外数据库即可启动）；接 MySQL 时设 SPRING_PROFILES_ACTIVE=mysql
ENV SPRING_PROFILES_ACTIVE=default
ENV SERVER_PORT=8080
EXPOSE 8080

# 容器内存受限时的合理默认值
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
