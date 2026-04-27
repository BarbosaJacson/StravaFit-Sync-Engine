package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.MinuteAnalysisEntity;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InsightService {

    private final GeminiClient geminiClient;

    public InsightService(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        String proximoTreinoData = calcularProximaDataTreino(activity.startDateLocal());
        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activity.startDateLocal(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    // Novo método para analisar a partir da Entidade do Banco
    public String getActivityInsightFromEntity(ActivityEntity entity) {
        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        String proximoTreinoData = calcularProximaDataTreino(entity.getStartDate());
        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, String startDate, List<StravaActivity.MinuteAnalysis> analysis, String proximaData) {
        DateTimeFormatter parser = DateTimeFormatter.ISO_INSTANT;
        ZonedDateTime date = ZonedDateTime.parse(startDate, parser.withZone(ZoneId.of("America/Sao_Paulo")));
        String dataFormatada = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        StringBuilder sb = new StringBuilder();
        sb.append("--- INSTRUÇÃO DE FORMATAÇÃO: NÃO USE ASTERISCOS (*) OU SÍMBOLOS DE MARKDOWN. USE APENAS TÍTULOS EM LETRAS MAIÚSCULAS E TRAÇOS PARA SEPARAR SEÇÕES ---\n\n");
        sb.append("DATA E HORA DO TREINO: ").append(dataFormatada).append("\n\n");
        sb.append("ETAPA 1: ANALISE DO TREINO ATUAL\n");
        sb.append("DADOS: ").append(name).append(" | ").append(String.format("%.1f km", distance)).append("\n");
        sb.append("PARAMETROS ZONA ALVO Z2: 127-138 BPM\n\n");
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis m = analysis.get(i);
            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", m.minute(), m.averageHeartRate(), m.averageElevation(), m.averageCadence()));
        }
        sb.append("\n\nETAPA 2: FEEDBACK E PRESCRIÇÃO\n");
        sb.append("1. Diagnóstico sincero do controle Z2 e análise de picos vs terreno.\n");
        sb.append("2. PRESCRIÇÃO PARA O PRÓXIMO TREINO EM: ").append(proximaData).append("\n");
        sb.append("3. Sugestão de Treino de Choque semanal se necessário.\n");
        return sb.toString();
    }

    private String calcularProximaDataTreino(String dataAtualStr) {
        LocalDate hoje = ZonedDateTime.parse(dataAtualStr).toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy"));
    }
}
