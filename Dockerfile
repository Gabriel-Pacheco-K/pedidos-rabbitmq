# Imagem de um dos serviços. Uso: docker build --build-arg SERVICE=order-service .

# Etapa 1: build com Maven (o reator compila o common junto)
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /app
ENV MAVEN_OPTS="-Xmx512m"
COPY pom.xml .
COPY common/pom.xml common/
COPY order-service/pom.xml order-service/
COPY inventory-service/pom.xml inventory-service/
COPY payment-service/pom.xml payment-service/
RUN mvn -B -q dependency:go-offline -pl ${SERVICE} -am
COPY common/src common/src
COPY ${SERVICE}/src ${SERVICE}/src
# Os testes rodam no CI; aqui só empacota
RUN mvn -B -q package -pl ${SERVICE} -am -DskipTests

# Etapa 2: só o JRE e o jar, rodando com usuário sem privilégios
FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/${SERVICE}/target/${SERVICE}-*.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
