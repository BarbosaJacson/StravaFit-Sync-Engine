package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class InsightService {

    private final GeminiClient geminiClient;

    public InsightService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        String prompt = buildPrompt(activity, analysis);
        return geminiClient.getInsight(prompt);
    }

    private String buildPrompt(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analise o seguinte treino de ").append(activity.sportType()).append(" no Strava:\n");
        sb.append("- Nome: ").append(activity.name()).append("\n");
        sb.append("- Distância: ").append(String.format("%.2f km", activity.distanceKm())).append("\n");
        sb.append("- Tempo Total: ").append(activity.elapsedTimeMinutes()).append(" minutos\n");
        sb.append("- FC Média: ").append(activity.averageHeartRate()).append(" bpm\n");
        sb.append("- FC Máxima: ").append(activity.maxHeartRate()).append(" bpm\n\n");

        sb.append("Detalhamento por minuto (Amostra):\n");
        // Mandamos apenas os primeiros 20 minutos ou o que couber para não estourar o limite de tokens desnecessariamente
        analysis.stream().limit(30).forEach(m -> {
            sb.append(String.format("Min %d: FC %.0f bpm, Zona %d, Elevação %.1f m\n", 
                m.minute(), m.averageHeartRate(), m.zone(), m.averageElevation()));
        });

        sb.append("\nCom base nesses dados e nas zonas de Karvonen (Z1-Z5), forneça um insight técnico, motivacional e curto (máximo 4 parágrafos) sobre a performance e o condicionamento do atleta.");
        
        return sb.toString();
    }
}
