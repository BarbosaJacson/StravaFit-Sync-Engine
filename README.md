# 🏃‍♂️ StravaFit — Integration & Fitness Analytics API

![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=java&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-47A248?style=for-the-badge&logo=mongodb&logoColor=white)
![Hibernate](https://img.shields.io/badge/Hibernate-JPA-59666C?style=for-the-badge&logo=hibernate&logoColor=white)

O **StravaFit** é uma API RESTful desenvolvida em Java e Spring Boot para centralizar, estruturar e sincronizar métricas de desempenho esportivo oriundas de plataformas de treino (como Garmin e Strava), facilitando a análise continuada de dados de saúde e integração com ecossistemas como o Google Fit.

---

## 🎯 Problema e Solução

Atletas e praticantes de corrida utilizam múltiplos ecossistemas para monitorar seus treinos (Garmin Connect, Strava, etc.). O **StravaFit** atua como uma camada intermediária (*staging & integration API*), normalizando e armazenando dados como:
- Pace médio e fracionado
- Zonas de Frequência Cardíaca (HR Zones)
- VO2 Max e métricas fisiológicas
- Distância, tempo total e elevação

Através desta estrutura, o projeto possibilita consultas performáticas e staging de dados limpos para futuras sincronizações automáticas.

---

## 🛠️ Tecnologias e Ferramentas

- **Linguagem:** Java 21
- **Framework:** Spring Boot 3
- **Persistência de Dados:** Spring Data JPA / Hibernate | Spring Data MongoDB
- **Bancos de Dados:** MySQL & MongoDB
- **Build & Dependências:** Maven
- **Testes de API:** Postman / Insomnia

---

## 🏗️ Arquitetura do Projeto

O sistema foi desenhado seguindo a arquitetura em camadas tradicional do Spring Boot, garantindo isolamento de responsabilidades e facilidade de manutenção:

StravaFit-Sync-Engine/
├── controller/     # Endpoints REST (HTTP requests/responses)
├── service/        # Regras de negócio, cálculos de zonas e conversões
├── repository/     # Interfaces JPA e MongoRepository para consultas
├── model/          # Entidades de domínio (RunningActivity, HeartRateZone, etc.)
└── dto/            # Data Transfer Objects para transporte limpo de dados

---

## 📊 Principais Funcionalidades

- [x] **Cadastro e Gestão de Corridas:** Endpoints para registrar treinos completos com distância, tempo e calorias.
- [x] **Métricas Avançadas:** Armazenamento detalhado de batimentos médios/máximos, pace e zonas de FC.
- [x] **Modelagem Híbrida de Dados:** Estrutura relacional (MySQL) para dados estruturados e documental (MongoDB) para logs/métricas flexíveis.
- [ ] **Sincronização com Google Fit API:** *(Em desenvolvimento)*
- [ ] **Dashboard de Métricas Semanais/Mensais:** *(Em desenvolvimento)*

---

## 🚀 Como Executar o Projeto Localmente

### Pré-requisitos
- JDK 21 instalado
- MySQL e MongoDB ativos na máquina
- Maven instalado (ou wrapper do projeto)

### Passos
1. **Clonar o repositório:**
   git clone https://github.com/BarbosaJacson/StravaFit-Sync-Engine.git
   cd StravaFit-Sync-Engine

2. **Configurar os Bancos de Dados:**
   Ajuste as credenciais no arquivo `src/main/resources/application.properties`:

   # Configuração MySQL
   spring.datasource.url=jdbc:mysql://localhost:3306/stravafit_db?createDatabaseIfNotExist=true
   spring.datasource.username=SEU_USUARIO
   spring.datasource.password=SUA_SENHA
   spring.jpa.hibernate.ddl-auto=update

   # Configuração MongoDB
   spring.data.mongodb.uri=mongodb://localhost:27017/stravafit_db

3. **Executar a aplicação:**
   mvn spring-boot:run

   A API estará rodando em `http://localhost:8080`.

---

👨‍💻 **Desenvolvido por Jacson Barbosa**  
[![LinkedIn](https://img.shields.io/badge/LinkedIn-0077B5?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/)
[![GitHub](https://img.shields.io/badge/GitHub-100000?style=for-the-badge&logo=github&logoColor=white)](https://github.com/BarbosaJacson)