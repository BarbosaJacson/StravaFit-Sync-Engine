package jackson.stravafit.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiClient {

    private final RestClient restClient;
    private final String apiKey;
    private static final int MAX_RETRIES = 3;

    public GeminiClient(RestClient.Builder builder, @Value("${gemini.api.key}") String apiKey) {
        // 1. Criamos a fábrica definindo a paciência de 5 minutos (300000ms) para ler os dados
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(300000);
        requestFactory.setConnectTimeout(30000); // 30 segundos para conectar

        // 2. Construímos o RestClient passando essa fábrica configurada
        this.restClient = builder
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestFactory(requestFactory) // <--- ESTA LINHA É A CHAVE
                .build();

        this.apiKey = apiKey;
    }

    public String getInsight(String prompt) {
        return getInsightWithRetry(prompt);
    }

    private String getInsightWithRetry(String prompt) {
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            )
        );

        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                // Retorno tipado corretamente com ParameterizedTypeReference para evitar warnings
                Map<String, Object> response = restClient.post()
                        .uri("/v1beta/models/gemini-2.5-flash:generateContent?key={key}", apiKey) // <-- Mudei para 2.5-flash
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                return response != null ? extractTextFromResponse(response) : "Resposta vazia da IA.";
            } catch (Exception e) {
                log.error("[GEMINI] Tentativa {} falhou: {}", (i + 1), e.getMessage());
                
                // Tratamento robusto de erros de rede e limites de cota
                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                boolean isNetworkError = e instanceof ResourceAccessException || 
                                         errorMsg.contains("Broken pipe") || 
                                         errorMsg.contains("Connection reset");
                boolean isRateLimit = errorMsg.contains("429") || errorMsg.contains("503");

                if (isNetworkError || isRateLimit) {
                    try {
                        log.info("[GEMINI] Falha de rede/cota detectada. Aguardando para nova tentativa...");
                        Thread.sleep(2000L * (i + 1)); 
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    break;
                }
            }
        }
        return "Erro persistente ao consultar o Gemini após várias tentativas.";
    }

    private String extractTextFromResponse(Map<String, Object> response) {
        try {
            // Uso de Generics para garantir segurança de tipos
            List<?> candidates = (List<?>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) return "Nenhum insight gerado.";
            Map<?, ?> firstCandidate = (Map<?, ?>) candidates.get(0);
            Map<?, ?> content = (Map<?, ?>) firstCandidate.get("content");
            List<?> parts = (List<?>) content.get("parts");
            Map<?, ?> firstPart = (Map<?, ?>) parts.get(0);
            return (String) firstPart.get("text");
        } catch (Exception e) {
            return "Erro ao processar resposta do Gemini.";
        }
    }
}
