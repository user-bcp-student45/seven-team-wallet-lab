FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM docker.io/library/eclipse-temurin:21-jre-alpine
RUN addgroup -S wallet && adduser -S wallet -G wallet
WORKDIR /app
COPY --from=build /src/target/wallet-backend-*.jar app.jar
USER wallet
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
