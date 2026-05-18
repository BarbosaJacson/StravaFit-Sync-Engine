package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.service.KnowledgeService; // Importar KnowledgeService
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    private final GeminiClient geminiClient;
    private final KnowledgeService knowledgeService; // Injetar KnowledgeService

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final int TETO_Z2_PADRAO = 138;

    private static final String NO_MARKDOWN_INSTRUCTION = 
        "--- REGRAS DE SAÍDA: Use apenas texto puro com quebras de linha e emojis. PROIBIDO o uso de blocos de código (```). ---";
    
    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        StringBuilder sb = new StringBuilder();
        sb.append(NO_MARKDOWN_INSTRUCTION).append("\n\n");
        sb.append("AVALIACAO PRE-TREINO: CONDICOES FISIOLOGICAS\n\n");
        sb.append("SITUAÇÃO DO SONO: ").append(sleepQuality.toUpperCase()).append("\n\n");
        sb.append("TAREFA:\n");
        sb.append("Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.\n");
        sb.append("Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.\n");
        sb.append("Forneça uma recomendação curta, direta e técnica.");
        
        return sanitizeOutput(geminiClient.getInsight(sb.toString()));
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        // Ajustado para usar getters, tratando o acesso privado relatado
        return generateInsight(
                activity.getName(), 
                activity.getDistance() / 1000.0, 
                activity.getStartDateLocal(), 
                activity.getAverageSpeed(), 
                analysis);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        // Cálculo seguro da velocidade média (km/h) para evitar divisão por zero
        Double averageSpeed = null;
        if (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) {
            double totalHours = entity.getTotalTimeMinutes() / 60.0;
            averageSpeed = entity.getDistanceKm() / totalHours;
        }
        
        return generateInsight(entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    private String generateInsight(String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(name, distance, activityDate, averageSpeed, analysis, proximoTreinoData);
        return sanitizeOutput(geminiClient.getInsight(prompt));
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, String proximoTreinoData) {
        String scientificContext = knowledgeService.getScientificContext(); // Recupera o material científico
        if (scientificContext == null || scientificContext.isBlank()) {
            log.error("ALERTA: Base de conhecimento (studySettings) está VAZIA no banco de dados!");
            scientificContext = "Use as diretrizes gerais de San-Millán para eficiência mitocondrial.";
        }

        String dataFormatada = date.format(BRAZIL_FORMATTER);
        double safeDistance = (distance != null) ? distance : 0.0;
        String paceFormatted = (averageSpeed != null && averageSpeed > 0) ? formatSpeedToPace(averageSpeed) : "N/A";
        
        // --- CÁLCULO DE MÉTRICAS PARA O MOTOR DE CLASSIFICAÇÃO ---
        double fcMedia = analysis.stream().mapToDouble(m -> m.getAverageHeartRate()).average().orElse(0.0);
        int duracao = analysis.size();
        
        // Cálculo de Desvio Padrão (Estabilidade)
        double variance = analysis.stream().mapToDouble(m -> Math.pow(m.getAverageHeartRate() - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Distribuição de Zonas
        long z2Count = analysis.stream().filter(m -> m.getZone() == 2).count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;

        // Tendência de FC (Primeira vs Segunda Metade)
        double firstHalf = analysis.stream().limit(duracao / 2).mapToDouble(m -> m.getAverageHeartRate()).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).mapToDouble(m -> m.getAverageHeartRate()).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";
        
        double ganhoAlt = analysis.stream().mapToDouble(m -> m.getAverageElevation()).max().orElse(0.0) - 
                          analysis.stream().mapToDouble(m -> m.getAverageElevation()).min().orElse(0.0);

        StringBuilder sb = new StringBuilder();
        
        sb.append("SISTEMA: Atue como o motor de classificação StravaFit. Sua tarefa é mapear os DADOS DO TREINO contra o CONTEXTO CIENTIFICO.\n\n");
        sb.append("REGRA DE OURO: Proibido inventar. Retorne estritamente o Diagnóstico Clínico-Esportivo contido no arquivo.\n\n");

        sb.append("<CONTEXTO_CIENTIFICO>\n");
        sb.append(scientificContext).append("\n\n");
        sb.append("</CONTEXTO_CIENTIFICO>\n\n");

        sb.append("<DADOS_DO_TREINO>\n");
        sb.append(String.format("- Volume: %d minutos | Distancia: %.1f KM\n", duracao, safeDistance));
        sb.append(String.format("- Frequência Cardíaca Média: %.0f bpm\n", fcMedia));
        sb.append(String.format("- Análise das Zonas: %.1f%% do tempo na Zona 2\n", z2Percent));
        sb.append(String.format("- Desvio Padrão da FC: %.1f bpm\n", stdDev));
        sb.append(String.format("- Altimetria: %.0f m | Pace Médio: %s\n", ganhoAlt, paceFormatted));
        sb.append(String.format("- Dinamica de FC: %s\n", comportamento));
        sb.append("</DADOS_DO_TREINO>\n\n");

        sb.append("TAREFA: Localize no <CONTEXTO_CIENTIFICO> qual CENÁRIO possui o 'Gatilho' que melhor descreve os <DADOS_DO_TREINO>. Retorne a resposta neste formato:\n\n");
        sb.append("🏃‍♂️ *StravaFit AI - Análise de Eficiência Mitocondrial*\n\n");
        sb.append("📌 *Cenário Detectado:* [Número] - [Título Exato]\n");
        sb.append("📊 *Métricas:* {tempo} min | FC Média: {fc} bpm | Pace: {pace}\n\n");
        sb.append("🩺 *Diagnóstico Fisiológico:*\n[Diagnóstico exato do arquivo]\n\n");

        // Manter a série temporal para que a IA possa validar o comportamento minuto a minuto
        sb.append(NO_MARKDOWN_INSTRUCTION).append("\n\n");
        sb.append("DADOS BRUTOS PARA VALIDAÇÃO DA SÉRIE TEMPORAL (Minuto: BPM/Alt/Cad):\n");
        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis m = analysis.get(i);
            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                    m.getMinute(), 
                    m.getAverageHeartRate(), 
                    m.getAverageElevation(), 
                    m.getAverageCadence()));
        }
        
        sb.append("\n\nPRÓXIMA PROGRAMAÇÃO: ").append(proximoTreinoData);
        
        return sb.toString();
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        // Remove asteriscos e hashtags comuns de Markdown
        return text.replaceAll("[*#]", "")
                   .trim();
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH; // Segundos por km
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d min/km", minutes, seconds);
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        // O método retorna String, garantindo compatibilidade com o que é esperado no prompt
        LocalDate hoje = date.toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.atStartOfDay(ZONE_SP).format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return ZonedDateTime.now(ZONE_SP);
        }

        try {
            // Tenta parsear como ISO_INSTANT (com Z no final)
            return ZonedDateTime.parse(dateStr, DateTimeFormatter.ISO_INSTANT.withZone(ZONE_SP));
        } catch (Exception e) {
            // Se falhar, tenta parsear como ISO_DATE_TIME (sem Z, com fuso horário)
            try {
                String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
                return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_DATE_TIME).atZone(ZONE_SP);
            } catch (Exception ex) {
                // Última tentativa: se for apenas data, adiciona um horário padrão
                try {
                    return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
                } catch (Exception exc) {
                    // Se tudo falhar, retorna o horário atual
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}
