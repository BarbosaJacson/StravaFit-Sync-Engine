package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InsightService {

    private final GeminiClient geminiClient;

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- INSTRUÇÃO DE FORMATAÇÃO: NÃO USE ASTERISCOS (*) OU SÍMBOLOS DE MARKDOWN. USE APENAS TÍTULOS EM LETRAS MAIÚSCULAS E TRAÇOS PARA SEPARAR SEÇÕES ---\n\n");
        sb.append("AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS\n\n");
        sb.append("SITUAÇÃO DO SONO: ").append(sleepQuality.toUpperCase()).append("\n\n");
        sb.append("TAREFA:\n");
        sb.append("Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.\n");
        sb.append("Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.\n");
        sb.append("Forneça uma recomendação curta, direta e técnica.");
        
        return geminiClient.getInsight(sb.toString());
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        String proximoTreinoData = calcularProximaDataTreino(activity.startDateLocal());
        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activity.startDateLocal(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        String proximoTreinoData = calcularProximaDataTreino(entity.getStartDate());
        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, String startDate, List<StravaActivity.MinuteAnalysis> analysis, String proximaData) {
        // Usamos o método auxiliar para parsear corretamente o horário
        ZonedDateTime date = parseToZonedDateTime(startDate);
        String dataFormatada = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        StringBuilder sb = new StringBuilder();

        sb.append("--- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---\n\n");
        
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

    private String calcularProximaDataTreino(String dataAtualStr) {
        LocalDate hoje = parseToZonedDateTime(dataAtualStr).toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy"));
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        // Peculiaridade da API do Strava: o campo 'start_date_local' chega com o sufixo 'Z',
        // o que faria o Java subtrair o fuso (ex: -3h) se usássemos ZonedDateTime.parse().
        // Como o dado 'start_date_local' já representa a hora da atividade, ignoramos o fuso.
        
        // Pegamos apenas os primeiros 19 caracteres (yyyy-MM-ddTHH:mm:ss)
        // Isso remove o 'Z' ou qualquer offset, tratando o tempo como local puro.
        String localPart = dateStr.substring(0, 19);
        
        return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.of("America/Sao_Paulo"));
    }
}
