package jackson.stravafit.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class GeminiClient {

    private final RestClient restClient;
    private final String apiKey;

    public GeminiClient(RestClient.Builder builder, @Value("${gemini.api.key}") String apiKey) {
        this.restClient = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.apiKey = apiKey;
    }

    public String getInsight(String prompt) {
        return getInsightWithRetry(prompt, 3); // Tenta até 3 vezes
    }

    private String getInsightWithRetry(String prompt, int retries) {
        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            )
        );

        for (int i = 0; i < retries; i++) {
            try {
                // Trocando para gemini-pro-latest para maior estabilidade
                Map<String, Object> response = restClient.post()
                        .uri("/v1beta/models/gemini-pro-latest:generateContent?key={key}", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(Map.class);

                return extractTextFromResponse(response);
            } catch (Exception e) {
                System.err.println("   [GEMINI] Tentativa " + (i + 1) + " falhou: " + e.getMessage());
                
                if (e.getMessage().contains("429") || e.getMessage().contains("503")) {
                    try {
                        Thread.sleep(5000L * (i + 1));
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
            List candidates = (List) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) return "Nenhum insight gerado.";
            Map firstCandidate = (Map) candidates.get(0);
            Map content = (Map) firstCandidate.get("content");
            List parts = (List) content.get("parts");
            Map firstPart = (Map) parts.get(0);
            return (String) firstPart.get("text");
        } catch (Exception e) {
            return "Erro ao processar resposta do Gemini.";
        }
    }
}
