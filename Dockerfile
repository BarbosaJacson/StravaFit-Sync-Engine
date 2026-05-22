# Estágio 1: Build (Compilação)
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copia apenas o pom.xml primeiro para baixar as dependências e usar o cache do Docker
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Agora copia o código e gera o arquivo .jar
COPY src ./src
RUN mvn clean package -DskipTests

# Estágio 2: Runtime (Execução)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Instala o curl para que o Google Cloud possa checar se a app está saudável
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Cria um usuário comum para rodar a app (Segurança: evita rodar como root)
RUN useradd -m spring
USER spring

COPY --from=build /app/target/*.jar app.jar

# O Cloud Run exige que a aplicação ouça na porta definida pela variável de ambiente $PORT
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-Dserver.port=${PORT:8080}", "-jar", "app.jar"]