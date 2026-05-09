package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
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

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final String NO_MARKDOWN_INSTRUCTION = 
        "--- REGRAS: USE APENAS NEGRITO (**) PARA TÍTULOS. NÃO USE '#'. SEJA CURTO, TÉCNICO E USE TÓPICOS. RESPOSTA MÁXIMA: 250 PALAVRAS. ---\n\n";

    private static final int TETO_Z2_PADRAO = 138;

    private final GeminiClient geminiClient;

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        log.info("Gerando recomendação pré-treino para qualidade de sono: {}", sleepQuality);
        StringBuilder sb = new StringBuilder();
        sb.append(NO_MARKDOWN_INSTRUCTION);
        sb.append("**AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS**\n\n");
        sb.append("**SONO**: ").append(sleepQuality.toUpperCase()).append("\n");
        sb.append("**CONTEXTO**: Treino Z2 (objetivo >75% na zona) às 05:30.\n");
        sb.append("**TAREFA**: Avalie o impacto fisiológico do sono e prescreva: Manter Plano, Reduzir Volume (50%) ou Descanso.\n");
        sb.append("Seja extremamente direto.");
        
        return geminiClient.getInsight(sb.toString());
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        log.info("Iniciando análise de insight para atividade Strava: {}", activity.name());
        ZonedDateTime activityDate = parseToZonedDateTime(activity.startDateLocal());
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activityDate, analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            log.warn("Atividade {} não possui detalhes de minutos para análise.", entity.getId());
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        log.info("Recuperando insight do banco de dados para atividade ID: {}", entity.getId());
        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        ZonedDateTime activityDate = parseToZonedDateTime(entity.getStartDate());
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), activityDate, analysis, proximoTreinoData);
        return geminiClient.getInsight(prompt);
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, List<StravaActivity.MinuteAnalysis> analysis, String proximaData) {
        log.debug("Construindo prompt profissional para '{}' do dia {}", name, date);
        String dataFormatada = date.format(BRAZIL_FORMATTER);

        double safeDistance = (distance != null) ? distance : 0.0;
        StringBuilder sb = new StringBuilder();

        sb.append(NO_MARKDOWN_INSTRUCTION);
        
        sb.append("**RELATÓRIO DE ANÁLISE DE TREINO**\n\n");
        sb.append("DATA: ").append(dataFormatada).append("\n");

        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", safeDistance)).append("\n");
        
        sb.append(String.format("PARAMETROS DE REFERENCIA: Z2 (127 - 137 BPM), Teto %d BPM. OBJETIVO TÉCNICO: Permanecer na Z2 por pelo menos 75%% do tempo.\n\n", TETO_Z2_PADRAO));
        
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        analysis.stream()
                .filter(m -> m.minute() % 2 == 0)
                .forEach(m -> sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                        m.minute(), m.averageHeartRate(), m.averageElevation(), m.averageCadence())));

        // Cálculo prévio de métricas para ajudar a IA
        long tempoAcimaTeto = analysis.stream().filter(m -> m.averageHeartRate() > TETO_Z2_PADRAO).count();
        double percentualAcima = analysis.isEmpty() ? 0 : (tempoAcimaTeto * 100.0) / analysis.size();

        sb.append("\n**ETAPA 1: ANALISE TÉCNICA SUCINTA**\n");
        sb.append(String.format("- **ZONAS**: %.1f%% acima do teto de %d BPM.\n", percentualAcima, TETO_Z2_PADRAO));
        sb.append("- **RITMO (PACE)**: Calcule o pace médio (min/km). Correlacione o aumento do BPM com o pace e a altimetria.\n");
        sb.append("- **CARDIAC DRIFT**: Deriva entre 1a e 2a metade (Teto 5%).\n");
        sb.append("- **CORRELAÇÃO**: O aumento de BPM foi por ladeira, aumento de ritmo ou perda de eficiência?\n");
        sb.append("- **ECONOMIA**: Cadência vs BPM.\n");
        sb.append("**AVALIAÇÃO**: Se >75% em Z2, o treino é um SUCESSO. Não critique variações <10%.\n\n");

        sb.append("**ETAPA 2: DIAGNÓSTICO E PRESCRIÇÃO**\n");
        sb.append("1. **DIAGNÓSTICO**: Eficiência Z2 e Fadiga.\n");
        sb.append("2. **PRÓXIMO TREINO** (").append(proximaData).append("): Alvo de Distância e Pace.\n");
        sb.append("3. **HIIT**: Apenas se houver prontidão.\n");
        sb.append("4. **NUTRIÇÃO**: Foco em **PÓS-TREINO**, **HIDRATAÇÃO** e **SUPLEMENTAÇÃO**.\n");

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
        if (dateStr == null || dateStr.isBlank()) {
            log.warn("Data da atividade nula ou vazia. Usando horário atual.");
            return ZonedDateTime.now(ZONE_SP);
        }

        try {
            String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
            return LocalDateTime.parse(localPart, DateTimeFormatter.ISO_DATE_TIME).atZone(ZONE_SP);
        } catch (Exception e) {
            log.error("Erro ao converter data: {}. Erro: {}", dateStr, e.getMessage());
            return ZonedDateTime.now(ZONE_SP);
        }
    }
}
