# Estágio 1: Compilação (Build)
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copia apenas o pom.xml primeiro para baixar as dependências (otimiza o cache do Docker)
COPY pom.xml .
RUN mvn dependency:go-offline

# Copia o código fonte e gera o pacote JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Estágio 2: Execução (Runtime) - Usando Alpine para ser mais leve e barato na nuvem
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Segurança: Cria um usuário de sistema para não rodar como root
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

# Otimização para containers: 
# UseContainerSupport permite que a JVM respeite os limites de memória do Docker
# MaxRAMPercentage define que o Java usará no máximo 75% da RAM do container
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]