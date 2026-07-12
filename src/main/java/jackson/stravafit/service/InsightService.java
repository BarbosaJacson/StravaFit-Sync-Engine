package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.repository.ActivitySummaryRepository;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final ActivitySummaryRepository activitySummaryRepository;
    private final KnowledgeService knowledgeService;
    private final InsightService self;
    private final int hrMaxConfig;
    private final int hrResting;
    private final String nomeAtleta;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    public InsightService(GeminiClient geminiClient,
                          ActivityRepository activityRepository,
                          WorkoutPrescriptionRepository workoutPrescriptionRepository,
                          ActivitySummaryRepository activitySummaryRepository,
                          @Lazy InsightService self,
                          KnowledgeService knowledgeService,
                          @Value("${atleta.hr-max:173}") int hrMaxConfig,
                          @Value("${atleta.hr-resting:53}") int hrResting,
                          @Value("${atleta.nome:Jacson}") String nomeAtleta) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.activitySummaryRepository = activitySummaryRepository;
        this.self = self;
        this.knowledgeService = knowledgeService;
        this.hrMaxConfig = hrMaxConfig;
        this.hrResting = hrResting;
        this.nomeAtleta = nomeAtleta;
    }

    public String getPreWorkoutRecommendation(String sleepQuality) {
        String prompt = """
            --- INSTRUÇÃO DE FORMATAÇÃO: O RETORNO DEVE SER COESO, ORGANIZADO E BEM FORMATADO, UTILIZANDO TÍTULOS E SUBTÍTULOS EM LETRAS MAIÚSCULAS. NÃO USE ASTERISCOS OU OUTROS SÍMBOLOS DE MARKDOWN. ---

            AVALIAÇÃO PRÉ-TREINO: CONDIÇÕES FISIOLÓGICAS

            SITUAÇÃO DO SONO: %S

            TAREFA:
            Como um Especialista em Fisiologia, avalie esta qualidade de sono para um atleta que tem um treino de Zona 2 programado para hoje as 05:30 da manhã.
            Se o sono foi ruim ou muito ruim, sugira uma adaptação (redução de volume ou intensidade, ou até mesmo descanso total). Se o sono foi bom, reforce o plano.
            Forneça uma recomendação curta, direta e técnica.
            """.formatted(sleepQuality.toUpperCase());
        
        return geminiClient.getInsight(prompt);
    }

    @Transactional
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {
        return generateInsight(
                activity.getId(),
                activity.getName(),
                activity.getDistance() != null ? activity.getDistance() / 1000.0 : 0.0,
                activity.getStartDateLocal(),
                activity.getAverageSpeed() != null ? activity.getAverageSpeed() * 3.6 : 0.0,
                analysis);
    }

    @Transactional
    public String getActivityInsightFromEntity(ActivityEntity entity) {
        if (entity.getMinuteDetails() == null || entity.getMinuteDetails().isEmpty()) {
            return "Erro: Dados de telemetria insuficientes para gerar análise.";
        }

        List<StravaActivity.MinuteAnalysis> analysis = entity.getMinuteDetails().stream()
                .map(m -> new StravaActivity.MinuteAnalysis(m.getMinute(), m.getAverageHeartRate(), m.getMaxHeartRate(), m.getZone(), m.getAverageElevation(), m.getAverageCadence()))
                .toList();

        Double averageSpeed = (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) ?
                entity.getDistanceKm() / (entity.getTotalTimeMinutes() / 60.0) : null;

        return generateInsight(entity.getId(), entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    private String generateInsight(Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        SessionMetrics metrics = calcularMetricasSessao(analysis, distance, averageSpeed);
        Optional<WorkoutPrescriptionEntity> prescricaoAnterior = workoutPrescriptionRepository.findTopByScheduledDateOrderByCreatedAtDesc(activityDate.toLocalDate());

        String prompt = buildProfessionalPrompt(name, metrics, activityDate, proximoTreinoData, prescricaoAnterior.orElse(null));
        String rawAiResponse = geminiClient.getInsight(prompt);
        String cleanResult = removeXmlBlock(rawAiResponse);

        self.persistirDadosTecnicos(activityId, activityDate, metrics, cleanResult, rawAiResponse);

        return sanitizeOutput(cleanResult);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistirDadosTecnicos(Long activityId, ZonedDateTime activityDate, SessionMetrics metrics, String cleanResult, String rawAiResponse) {
        try {
            ActivitySummaryEntity summary = activitySummaryRepository.findById(activityId).orElse(new ActivitySummaryEntity());
            summary.setActivityId(activityId);
            summary.setStartDate(activityDate.toLocalDateTime());
            summary.setDistanceKm(metrics.safeDistance());
            summary.setTotalTimeMinutes(metrics.duracao());
            summary.setAverageHeartRate(metrics.fcMedia());
            summary.setMaxHeartRate((int) metrics.fcMax());
            summary.setDominantZone(metrics.zonaPredominante());
            summary.setEfficiencyIndex(metrics.efficiencyIndex());
            summary.setAiAnalysisSummary(sanitizeOutput(cleanResult));
            activitySummaryRepository.saveAndFlush(summary);
            log.info("[DB] Sumário de performance persistido para atividade: {}", activityId);

            self.extractAndSavePrescription(activityId, rawAiResponse);
        } catch (Exception e) {
            log.error("[DB] Falha ao persistir dados técnicos: {}", e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extractAndSavePrescription(Long activityId, String rawAiResponse) {
        try {
            String xml = extractXmlBlock(rawAiResponse, "prescription_data");
            if (xml == null) {
                log.warn("[PRESCRIPTION] Bloco XML <prescription_data> não encontrado na resposta da IA para a atividade {}", activityId);
                return;
            }

            String scheduledDateStr = extractTagValue(xml, "scheduled_date");
            if (scheduledDateStr == null) {
                log.error("[PRESCRIPTION] Tag <scheduled_date> não encontrada no bloco XML para a atividade {}", activityId);
                return;
            }

            WorkoutPrescriptionEntity prescription = workoutPrescriptionRepository.findByActivityId(activityId).orElse(new WorkoutPrescriptionEntity());
            prescription.setActivityId(activityId);
            prescription.setScheduledDate(LocalDate.parse(scheduledDateStr.replaceAll("[^0-9-]", "")));
            prescription.setType(extractTagValue(xml, "type"));
            prescription.setDuration(extractTagValue(xml, "duration"));
            prescription.setIntensity(extractTagValue(xml, "intensity"));
            prescription.setFocus(extractTagValue(xml, "focus"));
            prescription.setMethod(extractTagValue(xml, "method"));
            prescription.setRawGeminiResponse(rawAiResponse);
            
            workoutPrescriptionRepository.saveAndFlush(prescription);
            log.info("[DB] Prescrição salva com sucesso para a data: {}", prescription.getScheduledDate());

        } catch (Exception e) {
            log.error("[PRESCRIPTION] Falha ao extrair ou salvar a prescrição: {}", e.getMessage(), e);
        }
    }

    private String buildProfessionalPrompt(String name, SessionMetrics metrics, ZonedDateTime date, String proximoTreinoData, WorkoutPrescriptionEntity prescricaoAnterior) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        String scientificContext = knowledgeService.getScientificContext();
        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        double histVo2Medio = historico.stream().mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig) / (double) hrResting)).average().orElse(0.0);
        double histFcMaxMedia = historico.stream().mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : 0).average().orElse(0.0);
        double histFcMediaGeral = historico.stream().mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0).average().orElse(0.0);
        double histPaceMedioSegundos = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm()).average().orElse(0.0);
        double histEfficiencyIndex = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getAverageHeartRate() != null && a.getAverageHeartRate() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getDistanceKm() * 1000) / (a.getAverageHeartRate() * a.getTotalTimeMinutes())).average().orElse(0.0);

        StringBuilder sb = new StringBuilder();

        sb.append("VOCÊ É UM ANALISTA DE PERFORMANCE DE ELITE. SUA MISSÃO É ANALISAR OS DADOS DE TREINO E FORNECER FEEDBACKS TÉCNICOS SEGUINDO ESTRITAMENTE O FORMATO SOLICITADO.\n\n");
        sb.append("REGRA DE FORMATAÇÃO: GERE A RESPOSTA USANDO APENAS TEXTO PURO, TÍTULOS EM MAIÚSCULAS E QUEBRAS DE LINHA. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN (ASTERISCOS, HASHTAGS, ETC.).\n\n");
        
        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO ---\n").append(scientificContext).append("\n\n");
        }

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", metrics.safeDistance())).append(" | Pace Médio: ").append(metrics.paceFormatted()).append("\n");
        
        sb.append("\n\n--- TAREFA PARA A IA ---\n");
        sb.append("1. Analise os 'DADOS DO TREINO ATUAL'.\n");
        sb.append("2. Com base na sua análise e na 'BASE DE CONHECIMENTO', identifique o 'CENÁRIO' do treino.\n");
        sb.append("3. Construa a resposta final preenchendo ESTRITAMENTE o formato abaixo.\n\n");

        sb.append("--- FORMATO DE SAÍDA OBRIGATÓRIO ---\n");
        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n");
        sb.append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [PREENCHA AQUI COM O TÍTULO DO CENÁRIO IDENTIFICADO]\n\n");
        sb.append("⚡ Intensidade do Estímulo: [ANALISE O DESVIO PADRÃO DA FC (").append(String.format("%.1f", metrics.stdDev())).append(" bpm) E O PICO DE FC (").append(String.format("%.0f%%", metrics.fcMaxPercentage())).append(" da FC Máx) PARA CLASSIFICAR A INTENSIDADE.]\n\n");
        
        sb.append("📊 Métricas: ").append(String.format("%.1f km", metrics.safeDistance())).append(" | ").append(metrics.duracao()).append(" min | FC Méd: ").append(String.format("%.0f", metrics.fcMedia())).append(" bpm | FC Max: ").append(String.format("%.0f", metrics.fcMax())).append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante()).append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append(" | Pace: ").append(metrics.paceFormatted()).append("\n\n");
        
        if (prescricaoAnterior != null) {
            sb.append("📋 Referência (Treino Anterior Prescrito):\n");
            sb.append("- Tipo Planejado: ").append(prescricaoAnterior.getType()).append("\n");
            sb.append("- Duração/Volume: ").append(prescricaoAnterior.getDuration()).append("\n");
            sb.append("- Intensidade Alvo: ").append(prescricaoAnterior.getIntensity()).append("\n");
            sb.append("- Foco Técnico: ").append(prescricaoAnterior.getFocus()).append("\n\n");
        }
        
        sb.append("📊 Histórico Médio (Últimos 10 treinos):\n");
        sb.append("- VO2 Máx Médio: ").append(String.format("%.1f", histVo2Medio)).append(" ml/kg/min\n");
        sb.append("- FC Máxima Média: ").append(String.format("%.0f", histFcMaxMedia)).append(" bpm\n");
        sb.append("- FC Média Geral: ").append(String.format("%.0f", histFcMediaGeral)).append(" bpm\n");
        sb.append("- Pace Médio: ").append(formatSecondsToPace(histPaceMedioSegundos)).append(" min/km\n");
        sb.append("- Eficiência Média: ").append(String.format("%.3f", histEfficiencyIndex)).append(" (m/bpm*min)\n\n");
        
        sb.append("1.0 - STATUS DO TREINO (CUMPRIMENTO DO PLANO):\n");
        sb.append("[ANALISE A 'SERIE TEMPORAL' PARA DETECTAR PICOS DE FC...]\n\n");
        
        sb.append("2.0 - DIAGNÓSTICO TÉCNICO FISIOLÓGICO PARA ").append(nomeAtleta).append(":\n");
        sb.append("[BUSQUE NA 'BASE DE CONHECIMENTO' O CENÁRIO...]\n\n");
        
        sb.append("3.0 - ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (").append(nomeAtleta).append("):\n");
        sb.append("[O 'efficiency_index' calculado para este treino foi de ").append(String.format("%.3f", metrics.efficiencyIndex())).append("...]\n\n");
        
        sb.append("4.0 - CONCLUSÃO E PRÓXIMO PASSO PARA ").append(nomeAtleta).append(":\n");
        sb.append("[FAÇA UMA CONCLUSÃO BREVE E MOTIVACIONAL...]\n\n");
        
        sb.append("5.0 - NUTRIÇÃO / DESCANSO\n");
        sb.append("[CONSULTE A 'BASE DE CONHECIMENTO'...]\n\n");
        
        sb.append("6.0 --- PRESCRIÇÃO STRAFIT PREDICT PARA ").append(proximoTreinoData.toUpperCase()).append(" ---\n");
        sb.append("[DIRETRIZES OBRIGATÓRIAS DE PRESCRIÇÃO...]\n\n");

        sb.append("--- INSTRUÇÃO TÉCNICA DO SISTEMA ---\n");
        sb.append("Ao final do relatório, adicione OBRIGATORIAMENTE um bloco XML com os dados da prescrição criada:\n");
        sb.append("<prescription_data>\n");
        sb.append("  <scheduled_date>").append(parseNextWorkoutDate(proximoTreinoData).format(DateTimeFormatter.ISO_LOCAL_DATE)).append("</scheduled_date>\n");
        sb.append("  <type>[Tipo do treino]</type>\n");
        sb.append("  <duration>[Duração]</duration>\n");
        sb.append("  <intensity>[Intensidade]</intensity>\n");
        sb.append("  <focus>[Foco]</focus>\n");
        sb.append("  <method>[Método]</method>\n");
        sb.append("</prescription_data>\n");

        return sb.toString();
    }

    public record SessionMetrics(double fcMedia, double fcMax, int duracao, double stdDev, int zonaPredominante, double z2Percent, String comportamento, double fcMaxPercentage, double vo2MaxEstimado, double ganhoAlt, double efficiencyIndex, double safeDistance, String paceFormatted) {}

    private SessionMetrics calcularMetricasSessao(List<StravaActivity.MinuteAnalysis> analysis, Double distance, Double averageSpeed) {
        double fcMedia = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double fcMax = analysis.stream().map(StravaActivity.MinuteAnalysis::getMaxHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0.0);
        int duracao = analysis.size();
        double variance = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(m -> Math.pow(m - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        int zonaPredominante = analysis.stream().map(StravaActivity.MinuteAnalysis::getZone).filter(Objects::nonNull).collect(Collectors.groupingBy(Function.identity(), Collectors.counting())).entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        long z2Count = analysis.stream().map(StravaActivity.MinuteAnalysis::getZone).filter(z -> z != null && z == 2).count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;
        double firstHalf = analysis.stream().limit(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";
        double fcMaxPercentage = (fcMax / hrMaxConfig) * 100;
        double vo2MaxEstimado = 15.3 * (fcMax / hrResting);
        DoubleSummaryStatistics elevStats = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageElevation).filter(Objects::nonNull).mapToDouble(Double::doubleValue).summaryStatistics();
        double ganhoAlt = elevStats.getMax() - elevStats.getMin();
        if (ganhoAlt < 0 || elevStats.getCount() == 0) ganhoAlt = 0.0;
        double safeDistance = distance != null ? distance : 0.0;
        double efficiencyIndex = (fcMedia > 0 && duracao > 0) ? (safeDistance * 1000.0) / (fcMedia * duracao) : 0.0;
        String paceFormatted = formatSpeedToPace(averageSpeed);
        return new SessionMetrics(fcMedia, fcMax, duracao, stdDev, zonaPredominante, z2Percent, comportamento, fcMaxPercentage, vo2MaxEstimado, ganhoAlt, efficiencyIndex, safeDistance, paceFormatted);
    }

    private String extractXmlBlock(String text, String blockName) {
        Pattern pattern = Pattern.compile("<" + blockName + ">(.*?)</" + blockName + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(0);
        }
        return null;
    }

    private String extractTagValue(String xml, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String removeXmlBlock(String text) {
        if (text == null) return "";
        // CORREÇÃO: Usa (?s) para que o '.' corresponda a quebras de linha, removendo o bloco XML de múltiplas linhas.
        return text.replaceAll("(?s)<prescription_data>.*?</prescription_data>", "").trim();
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#`]", "").trim();
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH;
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d min/km", minutes, seconds);
    }
    
    private String formatSecondsToPace(double totalSeconds) {
        if (totalSeconds <= 0) return "N/A";
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d", minutes, seconds);
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate today = LocalDate.now(ZONE_SP);
        LocalDate baseDate = date.toLocalDate().isBefore(today) ? today : date.toLocalDate();
        LocalDate dataIteracao = baseDate.plusDays(1);
        Set<DayOfWeek> diasDeTreino = Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);
        while (!diasDeTreino.contains(dataIteracao.getDayOfWeek())) {
            dataIteracao = dataIteracao.plusDays(1);
        }
        return dataIteracao.format(NEXT_WORKOUT_FORMATTER);
    }
    
    private LocalDate parseNextWorkoutDate(String proximoTreinoData) {
        // O formato é "EEEE, dd/MM/yyyy"
        return LocalDate.parse(proximoTreinoData.split(", ")[1], DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return ZonedDateTime.now(ZONE_SP);
        try {
            return LocalDateTime.parse(dateStr.substring(0, 19).replace(" ", "T")).atZone(ZONE_SP);
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
            } catch (Exception ex) {
                return ZonedDateTime.now(ZONE_SP);
            }
        }
    }
}
