package jackson.stravafit.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class TelegramClient {

    private final RestClient restClient;
    private final String chatId;

    public TelegramClient(RestClient.Builder builder, 
                          @Value("${telegram.bot.token}") String token,
                          @Value("${telegram.chat.id}") String chatId) {
        this.restClient = builder.baseUrl("https://api.telegram.org/bot" + token).build();
        this.chatId = chatId;
    }

    public void sendMessage(String text) {
        try {
            restClient.post()
                    .uri("/sendMessage")
                    .body(Map.of(
                            "chat_id", chatId,
                            "text", text,
                            "parse_mode", "Markdown"
                    ))
                    .retrieve()
                    .toBodilessEntity();
            System.out.println("   [TELEGRAM] Insight enviado com sucesso!");
        } catch (Exception e) {
            System.err.println("   [TELEGRAM] Erro ao enviar mensagem: " + e.getMessage());
        }
    }
}
