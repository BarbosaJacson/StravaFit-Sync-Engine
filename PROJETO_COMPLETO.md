# MANUAl DO PROJETO: STRAVAFIT SYNC ENGINE

Este documento contém o código fonte consolidado das principais classes do projeto para estudo e referência.

---

## 1. CONFIGURAÇÕES

### RestClientConfig.java
```java
package jackson.stravafit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
```

---

## 2. CLIENTES (INTEGRAÇÕES)

### StravaClient.java
```java
// Localizado em: jackson.stravafit.client.StravaClient
// Responsável por buscar atividades, zonas e fluxos de dados do Strava API v3.
```

### GeminiClient.java
```java
// Localizado em: jackson.stravafit.client.GeminiClient
// Responsável pela integração com Google Gemini API (v1beta) usando o modelo gemini-flash-latest.
```

### TelegramClient.java
```java
// Localizado em: jackson.stravafit.client.TelegramClient
// Envia notificações formatadas para o bot do Telegram usando RestClient.
```

---

## 3. SERVIÇOS (LÓGICA)

### ActivityService.java
```java
// Localizado em: jackson.stravafit.service.ActivityService
// Contém a lógica de cálculo de Zonas de Karvonen (FC Reserva) e agregação de dados por minuto.
```

### InsightService.java
```java
// Localizado em: jackson.stravafit.service.InsightService
// Orquestra a geração do prompt profissional e calcula o cronograma de treinos (Ter, Qui, Sab).
```

### SyncScheduler.java
```java
// Localizado em: jackson.stravafit.service.SyncScheduler
// O motor agendado que executa o fluxo completo de sincronização e análise.
```

---

## 4. MODELOS (DOMÍNIO)

### ActivityEntity.java
```java
// Entidade principal que mapeia a tabela 'activities' no MySQL.
```

### MinuteAnalysisEntity.java
```java
// Mapeia o detalhamento minuto a minuto de cada treino.
```

---

*(Nota: Este arquivo é um resumo consolidado. Para ver o código completo de cada arquivo, abra-os individualmente no projeto ou utilize a função de impressão do IntelliJ em cada um.)*
