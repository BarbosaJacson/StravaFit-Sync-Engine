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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

@Slf4j
@Component
public class GeminiClient {

    private final RestClient restClient;
    private final String apiKey;
    private final List<String> models;
    private final AtomicInteger currentModelIndex = new AtomicInteger(0);
    private static final int MAX_RETRIES = 3;


    public GeminiClient(RestClient.Builder builder, 
                        @Value("${gemini.api.key}") String apiKey,
                        @Value("${gemini.models.list:gemini-1.5-flash}") String modelsConfig) {
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
        this.models = Arrays.stream(modelsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
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

        // Loop principal para tentar gerar o insight, alternando modelos em caso de 429
        for (int totalAttempt = 0; totalAttempt < models.size() + 2; totalAttempt++) { 
            // Seleciona o modelo atual baseado no índice atômico
            String currentModel = models.get(currentModelIndex.get() % models.size());
            
            try {
                log.info("[GEMINI] Tentando consulta com o modelo: {}", currentModel);
                
                Map<String, Object> response = restClient.post()
                        .uri("/v1/models/{model}:generateContent?key={key}", currentModel, apiKey) 
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestBody)
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                return response != null ? extractTextFromResponse(response) : "Resposta vazia da IA.";
            } catch (Exception e) { // Captura qualquer exceção durante a chamada REST
                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                log.error("[GEMINI] Falha no modelo {}: {}", currentModel, errorMsg);

                // Tratamento robusto de erros de rede e limites de cota
                boolean isNetworkError = e instanceof ResourceAccessException || 
                                         errorMsg.contains("Broken pipe") || 
                                         errorMsg.contains("Connection reset");
                boolean isRateLimit = errorMsg.contains("429");
                boolean isNotFound = errorMsg.contains("404");
                boolean isServiceUnavailable = errorMsg.contains("503");

                // Se o erro for de cota (429) ou modelo não encontrado (404), rotacionamos imediatamente
                if (isRateLimit || isNotFound) {
                    currentModelIndex.incrementAndGet(); // Avança para o próximo modelo
                    log.warn("[GEMINI] Modelo {} falhou ({}). Tentando o próximo da lista...", currentModel, isRateLimit ? "429-Cota" : "404-NãoExiste");
                    
                    // Pequena pausa para não queimar as tentativas em milissegundos
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    
                    continue; // Pula para a próxima iteração do loop principal, que tentará o novo modelo
                } 
                // Para erros de rede ou serviço indisponível (503), tentamos novamente com o mesmo modelo após um delay
                else if (isNetworkError || isServiceUnavailable) {
                    try {
                        log.info("[GEMINI] Erro de rede no modelo {}. Aguardando reprocessamento...", currentModel);
                        Thread.sleep(3000L); 
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    break;
                }
            }
        } // Fim do loop principal
        log.error("[GEMINI] Todas as tentativas de geração de insight falharam após esgotar retries e rodízio de modelos.");
        return "Erro persistente ao gerar insight da IA após múltiplas tentativas.";
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
