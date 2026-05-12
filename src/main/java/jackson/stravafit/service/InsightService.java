package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        "--- REGRAS DE SAÍDA OBRIGATÓRIAS ---\n" +
        "1. EXCLUA TODO E QUALQUER MARKDOWN. PROIBIDO o uso de asteriscos (*), hífens (-) ou símbolos (#).\n" +
        "2. FORMATAÇÃO: Use APENAS letras MAIÚSCULAS para títulos. Não use negrito.\n" +
        "3. USE ESPAÇAMENTO ENTRE PARÁGRAFOS E LINHAS EM BRANCO PARA ORGANIZAR O TEXTO.\n\n";

    private static final int TETO_Z2_PADRAO = 138;

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;

    // Gerador de recomendação pré-treino baseado no sono
    public String getPreWorkoutRecommendation(String sleepQuality) {
        log.info("Gerando recomendação pré-treino para qualidade de sono: {}", sleepQuality);
        StringBuilder sb = new StringBuilder();
        sb.append(NO_MARKDOWN_INSTRUCTION);
        sb.append("AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS\n\n");
        sb.append("SITUAÇÃO DO SONO: ").append(sleepQuality.toUpperCase()).append("\n");
        sb.append("CONTEXTO: Treino Z2 (objetivo >75% na zona) às 05:30.\n");
        sb.append("TAREFA: Avalie o impacto fisiológico do sono e prescreva: Manter Plano, Reduzir Volume (50%) ou Descanso.\n");
        sb.append("Seja extremamente direto.");
        
        return sanitizeOutput(geminiClient.getInsight(sb.toString()));
    }

    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        log.info("Iniciando análise de insight para atividade Strava: {}", activity.name());
        ZonedDateTime activityDate = parseToZonedDateTime(activity.startDateLocal());
        String proximoTreinoData = calcularProximaDataTreino(activityDate);

        // Busca histórico para contexto
        String contextoHistorico = buscarResumoTreinoAnterior(activity.id());

        String prompt = buildProfessionalPrompt(activity.name(), activity.distanceKm(), activityDate, analysis, proximoTreinoData, contextoHistorico);
        return sanitizeOutput(geminiClient.getInsight(prompt));
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

        // Busca histórico para contexto
        String contextoHistorico = buscarResumoTreinoAnterior(entity.getId());

        String prompt = buildProfessionalPrompt(entity.getName(), entity.getDistanceKm(), activityDate, analysis, proximoTreinoData, contextoHistorico);
        return sanitizeOutput(geminiClient.getInsight(prompt));
    }

    private String buildProfessionalPrompt(String name, Double distance, ZonedDateTime date, List<StravaActivity.MinuteAnalysis> analysis, String proximaData, String historico) {
        log.debug("Construindo prompt profissional para '{}' do dia {}", name, date);
        String dataFormatada = date.format(BRAZIL_FORMATTER);

        double safeDistance = (distance != null) ? distance : 0.0;
        StringBuilder sb = new StringBuilder();

        sb.append(NO_MARKDOWN_INSTRUCTION);
        
        sb.append("RELATÓRIO DE ANÁLISE DE TREINO\n\n");
        sb.append("DATA E HORA: ").append(dataFormatada).append("\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f KM", safeDistance)).append("\n");
        
        sb.append(String.format("PARAMETROS DE REFERENCIA: Z2 (127 - %d BPM), Teto %d BPM. OBJETIVO TECNICO: Permanecer na Z2 por pelo menos 75%% do tempo.\n\n", TETO_Z2_PADRAO - 1, TETO_Z2_PADRAO));
        
        sb.append("CONTEXTO HISTÓRICO:\n");
        sb.append(historico != null ? historico : "Nenhum histórico anterior disponível para comparação.").append("\n\n");

        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        analysis.stream()
                .filter(m -> m.minute() % 2 == 0)
                .forEach(m -> sb.append(String.format("%d:%.0f/%.0fm/%.0f | ", 
                        m.minute(), m.averageHeartRate(), m.averageElevation(), m.averageCadence())));

        // Cálculo prévio de métricas para ajudar a IA
        long tempoAcimaTeto = analysis.stream().filter(m -> m.averageHeartRate() > TETO_Z2_PADRAO).count();
        double percentualAcima = analysis.isEmpty() ? 0 : (tempoAcimaTeto * 100.0) / analysis.size();

        sb.append("\nETAPA 1: ANALISE TECNICA DETALHADA E CORRELACAO DE VARIAVEIS\n");
        sb.append(String.format("- ZONAS: %.1f%% do tempo total operando acima do teto de %d BPM.\n", percentualAcima, TETO_Z2_PADRAO));

        sb.append("ANÁLISE DE DESEMPENHO CRONOLÓGICA: Analise a serie temporal e descreva a evolucao do treino em blocos de tempo. " +
                "Identifique momentos especificos (ex: Minuto 10 ao 25). " +
                "CORRELAÇÃO OBRIGATÓRIA: Relacione como a variação de ALTIMETRIA e PACE afetou o BPM e a permanência na ZONA. " +
                "Determine se o aumento de esforço foi mecânico (subida/velocidade) ou fisiológico (fadiga).\n");

        sb.append("CARDIAC DRIFT: Calcule a deriva cardiaca entre a primeira e segunda metade.\n");
        sb.append("ECONOMIA MECANICA: Estabilidade da cadencia vs oscilacoes de FC.\n");
        sb.append("AVALIACAO TECNICA FINAL: Classifique como SUCESSO se >75% na Z2.\n\n");

        sb.append("ETAPA 2: DIAGNOSTICO E PRESCRICAO\n");
        
        if (historico != null) {
            sb.append("1. AUDITORIA DE EXECUÇÃO: Compare a distância e zona de hoje com a meta estipulada no INSIGHT ANTERIOR. O atleta cumpriu o planejado?\n");
        } else {
            sb.append("1. AUDITORIA: Analise se a intensidade foi condizente com um treino de base.\n");
        }
        
        sb.append("2. DIAGNOSTICO: Eficiencia da Z2 e analise de fadiga acumulada.\n");
        sb.append("3. PLANEJAMENTO PARA O PROXIMO TREINO (").append(proximaData).append("): Ajuste distancia e pace alvo baseado no desempenho de hoje.\n");
        sb.append("4. NUTRICAO CONTEXTUAL: Recomendacoes curtas de POS-TREINO, HIDRATACAO e SUPLEMENTACAO.\n\n");

        sb.append("LEMBRETE: NAO USE SIBOLOS DE MARKDOWN. USE APENAS TEXTO BRUTO E ESPACAMENTO.");

        return sb.toString();
    }

    public String buscarResumoTreinoAnterior(Long currentId) {
        return activityRepository.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")))
                .stream()
                .filter(a -> !a.getId().equals(currentId))
                .map(a -> "TREINO ANTERIOR: " + a.getName() + " em " + a.getStartDate() + ". INSIGHT ANTERIOR: " + a.getGeminiInsight())
                .findFirst()
                .orElse(null);
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate hoje = date.toLocalDate();
        LocalDate proximo = hoje.plusDays(1);
        while (proximo.getDayOfWeek() != DayOfWeek.TUESDAY && proximo.getDayOfWeek() != DayOfWeek.THURSDAY && proximo.getDayOfWeek() != DayOfWeek.SATURDAY) {
            proximo = proximo.plusDays(1);
        }
        return proximo.format(NEXT_WORKOUT_FORMATTER);
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        // Remove asteriscos, hashtags e hífens repetidos que a IA usa para listas
        return text.replaceAll("[*#]", "")
                   .replaceAll("(?m)^\\s*-\\s*", "  ") // Transforma hífens de lista em espaços
                   .trim();
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
