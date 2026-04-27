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
        // Limite do Telegram é 4096. Vamos usar 4000 para segurança.
        int maxLength = 4000;
        
        try {
            if (text.length() <= maxLength) {
                sendToTelegram(text);
            } else {
                // Quebra a mensagem em partes
                for (int i = 0; i < text.length(); i += maxLength) {
                    int end = Math.min(i + maxLength, text.length());
                    sendToTelegram(text.substring(i, end));
                }
            }
        } catch (Exception e) {
            System.err.println("   [TELEGRAM] Erro ao enviar mensagem: " + e.getMessage());
        }
    }

    private void sendToTelegram(String text) {
        restClient.post()
                .uri("/sendMessage")
                .body(Map.of(
                        "chat_id", chatId,
                        "text", text
                ))
                .retrieve()
                .toBodilessEntity();
        System.out.println("   [TELEGRAM] Parte da mensagem enviada com sucesso!");
    }
}
