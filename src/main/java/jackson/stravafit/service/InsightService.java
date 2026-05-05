package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InsightService {

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final String NO_MARKDOWN_INSTRUCTION = 
        "--- INSTRUÇÃO DE FORMATAÇÃO: NÃO USE ASTERISCOS (*) OU SÍMBOLOS DE MARKDOWN. USE APENAS TÍTULOS EM LETRAS MAIÚSCULAS E TRAÇOS PARA SEPARAR SEÇÕES ---\n\n";

    private final GeminiClient geminiClient;

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        StringBuilder sb = new StringBuilder();
        sb.append(NO_MARKDOWN_INSTRUCTION);
        sb.append("AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS\n\n");
        sb.append("SITUAÇÃO DO SONO: ").append(sleepQuality.toUpperCase()).append("\n\n");
        sb.append("TAREFA:\n");
        sb.append("Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.\n");
        sb.append("Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.\n");
        sb.append("Forneça uma recomendação curta, direta e técnica.");
        
        return geminiClient.getInsight(sb.toString());
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(activity.startDateLocal());
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activityDate, analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        ZonedDateTime activityDate = parseToZonedDateTime(entity.getStartDate());
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), activityDate, analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, List<StravaActivity.MinuteAnalysis> analysis, String proximaData) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);

        StringBuilder sb = new StringBuilder();

        sb.append(NO_MARKDOWN_INSTRUCTION);
        
        sb.append("DATA E HORA DO TREINO: ").append(dataFormatada).append("\n\n");

        sb.append("ETAPA 1: ANALISE DO TREINO ATUAL\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", distance)).append("\n");
        sb.append("PARAMETROS DE REFERENCIA: Z2 (127 - 137 BPM), Teto 138 BPM.\n\n");
        
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        analysis.stream()
                .filter(m -> m.minute() % 2 == 0) // Amostragem a cada 2 minutos para economizar tokens
                .forEach(m -> sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                        m.minute(), m.averageHeartRate(), m.averageElevation(), m.averageCadence())));

        // Cálculo prévio de métricas para ajudar a IA
        long tempoAcimaTeto = analysis.stream().filter(m -> m.averageHeartRate() > 138).count();
        double percentualAcima = (tempoAcimaTeto * 100.0) / analysis.size();

        sb.append("\n\nTAREFAS DE ANALISE TECNICA:\n");
        sb.append(String.format("- Auditoria de Zonas: O atleta passou %.1f%% do tempo acima do teto de 138 BPM.\n", percentualAcima));
        sb.append("- Calculo do Desacoplamento Aerobico (Cardiac Drift): Comparar 1a vs 2a metade do treino. Se > 5%, sinalizar deriva.\n");
        sb.append("- Analise de GAP: Cruzar BPM com altimetria. Validar esforço em subidas.\n");
        sb.append("- Indicadores de Economia de Corrida: Correlacionar cadência com controle de BPM em Z2.\n\n");

        sb.append("ETAPA 2: FEEDBACK E PRESCRICAO TECNICA\n");
        sb.append("1. Diagnostico de Eficiencia Metabolica: Status da Z2 (Natural ou Forcado) e Analise de Fadiga Residual.\n");
        sb.append("2. Planejamento Adaptativo para ").append(proximaData).append(": Definir Distância, Pace Alvo e Método.\n");
        sb.append("3. Bloco de Estimulo a Biogenese Mitocondrial (HIIT): Prescrever se o treino atual foi leve.\n");
        sb.append("4. Recomendacao Nutricional Contextual: Ajuste de suplementação com base no esforço.\n");

        return sb.toString();
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate hoje = date.toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        // Peculiaridade da API do Strava: o campo 'start_date_local' chega com o sufixo 'Z',
        // o que faria o Java subtrair o fuso (ex: -3h) se usássemos ZonedDateTime.parse().
        // Como o dado 'start_date_local' já representa a hora da atividade, ignoramos o fuso.
        
        // Pegamos apenas os primeiros 19 caracteres (yyyy-MM-ddTHH:mm:ss)
        // Isso remove o 'Z' ou qualquer offset, tratando o tempo como local puro.
        // Verificação de segurança adicionada
        String localPart = (dateStr != null && dateStr.length() >= 19) ? dateStr.substring(0, 19) : dateStr;
        
        return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_DATE_TIME)
                .atZone(ZONE_SP);
    }
}
