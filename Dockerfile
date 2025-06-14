FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata
ENV TZ=Europe/Moscow
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring
WORKDIR /app
COPY --from=build /app/target/cleopatra-0.0.1-SNAPSHOT.jar app.jar


# Оптимизированные JVM настройки для Render
# Новые настройки для Render Free
ENV JAVA_OPTS="-Xmx300m -Xms128m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxGCPauseMillis=100"

EXPOSE 10000

# Убрана дублирующая настройка порта
CMD ["sh", "-c", "java $JAVA_OPTS -Dspring.profiles.active=prod -jar app.jar"]