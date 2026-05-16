package jackson.stravafit.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiClient {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiClient(RestClient restClient,
                        @Value("${openai.api.key}") String apiKey,
                        @Value("${openai.model:gpt-4o}") String model) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String getInsight(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "Atue como um analista de performance. Responda exclusivamente em JSON."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "response_format", Map.of("type", "json_object")
            );

            Map<String, Object> response = restClient.post()
                    .uri(OPENAI_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response == null || !response.containsKey("choices")) {
                log.error("Resposta da OpenAI inválida ou vazia.");
                return null;
            }

            List<?> choices = (List<?>) response.get("choices");
            if (choices == null || choices.isEmpty()) return null;

            Map<?, ?> firstChoice = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
            
            return message != null ? (String) message.get("content") : null;

        } catch (Exception e) {
            log.error("Erro ao chamar OpenAI: {}", e.getMessage());
            return null;
        }
    }
}