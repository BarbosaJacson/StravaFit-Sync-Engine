package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.repository.ActivitySummaryRepository;
import java.time.*;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Slf4j
@Service
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final KnowledgeService knowledgeService;
    private final int hrMaxConfig;
    private final int hrResting;
    private final int idadeAtleta;
    private final String nomeAtleta;
    private final InsightService self; // Injeção para garantir o funcionamento do @Transactional
    private final ActivitySummaryRepository activitySummaryRepository;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    private static final String NO_MARKDOWN_INSTRUCTION =
            "--- REGRAS DE SAÍDA: Use apenas texto puro com quebras de linha e emojis. PROIBIDO o uso de blocos de código (```). ---";

    public InsightService(GeminiClient geminiClient,
                          ActivityRepository activityRepository,
                          WorkoutPrescriptionRepository workoutPrescriptionRepository,
                          ActivitySummaryRepository activitySummaryRepository,
                          @Lazy InsightService self,
                          KnowledgeService knowledgeService,
                          @Value("${atleta.hr-max:173}") int hrMaxConfig,
                          @Value("${atleta.hr-resting:53}") int hrResting,
                          @Value("${atleta.idade:47}") int idadeAtleta,
                          @Value("${atleta.nome:Jacson}") String nomeAtleta) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.activitySummaryRepository = activitySummaryRepository;
        this.self = self;
        this.knowledgeService = knowledgeService;
        this.hrMaxConfig = hrMaxConfig;
        this.hrResting = hrResting;
        this.idadeAtleta = idadeAtleta;
        this.nomeAtleta = nomeAtleta;
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

        Double averageSpeed = null;
        if (entity.getDistanceKm() != null && entity.getTotalTimeMinutes() != null && entity.getTotalTimeMinutes() > 0) {
            double totalHours = entity.getTotalTimeMinutes() / 60.0;
            averageSpeed = entity.getDistanceKm() / totalHours;
        }

        return generateInsight(entity.getId(), entity.getName(), entity.getDistanceKm(), entity.getStartDate(), averageSpeed, analysis);
    }

    public String generateInsight(Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis) {
        // 1. Parse de data e preparação
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        String proximoTreinoData = calcularProximaDataTreino(activityDate);
        
        // 2. Processamento das métricas técnicas da sessão (Independente da IA)
        SessionMetrics metrics = calcularMetricasSessao(analysis, distance, averageSpeed, activityDate);

        log.info("[AI] Iniciando construção do prompt para atividade: {}", name);
        String prompt = buildProfessionalPrompt(name, metrics, activityDate, proximoTreinoData, analysis);

        // 3. Chamada ao Gemini
        String result = geminiClient.getInsight(prompt);
        log.info("[AI] Resposta da IA recebida com sucesso.");
        
        String cleanResult = removeXmlBlock(result);

        // Chamamos via 'self' (Proxy) para garantir que o isolamento de transação funcione na nuvem
        self.persistirDadosTecnicos(activityId, activityDate, metrics, cleanResult, result);

        return sanitizeOutput(cleanResult);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistirDadosTecnicos(Long activityId, ZonedDateTime activityDate, SessionMetrics metrics, String cleanResult, String rawAiResponse) {
        try {
            // Lógica de UPSERT: Busca existente ou cria novo para garantir atualização sem erro de duplicidade
            ActivitySummaryEntity summary = activitySummaryRepository.findById(activityId)
                    .orElse(new ActivitySummaryEntity());

            // 4. ATUALIZAÇÃO/PREENCHIMENTO DOS CAMPOS
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
            log.info("[DB] Sumário de performance persistido/atualizado para atividade: {}", activityId);

            // Importante: Chamar via 'self' também aqui para isolar a transação da prescrição
            self.extractAndSavePrescription(activityId, rawAiResponse, metrics.safeDistance());
        } catch (Exception e) {
            log.error("[DB] Falha crítica ao persistir dados técnicos, mas a análise será enviada: {}", e.getMessage());
        }
    }

    // Classe auxiliar interna para transportar as métricas processadas
    public record SessionMetrics(
            double fcMedia, 
            double fcMax, 
            int duracao, 
            double stdDev, 
            int zonaPredominante, 
            double z2Percent, 
            String comportamento, 
            double fcMaxPercentage, 
            double vo2MaxEstimado, 
            double ganhoAlt, 
            double efficiencyIndex, 
            double safeDistance, 
            String paceFormatted) {}

    private SessionMetrics calcularMetricasSessao(List<StravaActivity.MinuteAnalysis> analysis, Double distance, Double averageSpeed, ZonedDateTime date) {
        double fcMedia = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(0.0);

        double fcMax = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getMaxHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).max().orElse(0.0);

        int duracao = analysis.size();
        double variance = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(m -> Math.pow(m - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        int zonaPredominante = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(0);

        long z2Count = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getZone)
                .filter(z -> z != null && z == 2).count();
        double z2Percent = (duracao > 0) ? (z2Count * 100.0) / duracao : 0.0;

        double firstHalf = analysis.stream().limit(duracao / 2)
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(fcMedia);

        double secondHalf = analysis.stream().skip(duracao / 2)
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().orElse(fcMedia);

        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";
        double fcMaxPercentage = (fcMax / hrMaxConfig) * 100;
        double vo2MaxEstimado = 15.3 * ((double) hrMaxConfig / hrResting);

        // Otimização: Calcula min e max elevation em um único stream
        DoubleSummaryStatistics elevStats = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageElevation)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        
        double ganhoAlt = elevStats.getMax() - elevStats.getMin();
        if (ganhoAlt < 0 || elevStats.getCount() == 0) ganhoAlt = 0.0;

        double safeDistance = distance != null ? distance : 0.0;
        double efficiencyIndex = (fcMedia > 0 && duracao > 0) ? (safeDistance * 1000.0) / (fcMedia * duracao) : 0.0;
        String paceFormatted = formatSpeedToPace(averageSpeed);

        return new SessionMetrics(
                fcMedia,            // double fcMedia
                fcMax,              // double fcMax
                duracao,            // int duracao
                stdDev,             // double stdDev
                zonaPredominante,   // int zonaPredominante
                z2Percent,          // double z2Percent
                comportamento,      // String comportamento
                fcMaxPercentage,    // double fcMaxPercentage
                vo2MaxEstimado,     // double vo2MaxEstimado
                ganhoAlt,           // double ganhoAlt
                efficiencyIndex,    // double efficiencyIndex
                safeDistance,       // double safeDistance
                paceFormatted       // String paceFormatted
        );
    }

    private String buildProfessionalPrompt(String name, SessionMetrics metrics, ZonedDateTime date,
                                           String proximoTreinoData,
                                           List<StravaActivity.MinuteAnalysis> analysis) {
        String scientificContext = knowledgeService.getScientificContext();
        log.info("[KNOWLEDGE] Contexto científico enviado para IA: {} caracteres.", scientificContext != null ? scientificContext.length() : 0);
        if (scientificContext == null || scientificContext.isBlank()) {
            log.error("ALERTA: Base de conhecimento (studySettings) está VAZIA no banco de dados!");
            scientificContext = "Use as diretrizes gerais de San-Millán para eficiência mitocondrial.";
        }

        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        historico = historico.stream()
                .sorted(Comparator.comparing((ActivityEntity a) -> parseToZonedDateTime(a.getStartDate())).reversed())
                .toList();

        log.info("[AI] Histórico recuperado: {} atividades para cálculo de médias.", historico.size());

        double histVo2Medio = 0;
        double histFcMaxMedia = 0;
        double histFcMedioDasMedias = 0;
        double histPaceMedioSegundos = 0;
        double histEfficiencyIndex = 0;

        if (!historico.isEmpty()) {
            String datasHistorico = historico.stream()
                    .map(ActivityEntity::getStartDate)
                    .collect(Collectors.joining(", "));
            log.info("[AI] Datas do histórico processadas (ordem DESC): [{}]", datasHistorico);

            histFcMaxMedia = historico.stream()
                    .mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig)
                    .average().orElse(0);

            histFcMedioDasMedias = historico.stream()
                    .mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0)
                    .average().orElse(0);

            histVo2Medio = historico.stream()
                    .mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMaxConfig) / (double) hrResting))
                    .average().orElse(0);

            histPaceMedioSegundos = historico.stream()
                    .filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null)
                    .mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm())
                    .average().orElse(0);

            histEfficiencyIndex = historico.stream()
                    .filter(a -> a.getDistanceKm() != null && a.getAverageHeartRate() != null && a.getAverageHeartRate() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0)
                    .mapToDouble(a -> (a.getDistanceKm() * 1000.0) / (a.getAverageHeartRate() * a.getTotalTimeMinutes()))
                    .average().orElse(0);
        }

        String workoutIntensityType = determineWorkoutIntensityType(metrics.stdDev(), metrics.fcMaxPercentage());
        String dataFormatada = date.withZoneSameInstant(ZONE_SP).format(BRAZIL_FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("SISTEMA: Motor de Classificação Fisiológica StravaFit.\n");
        sb.append("ATIVIDADE: ").append(name).append("\n");
        sb.append(String.format("PERFIL DO ATLETA: %s, %d anos | FC Máx: %d | FC Repouso: %d | VO2 Est: %.1f\n", nomeAtleta, idadeAtleta, hrMaxConfig, hrResting, metrics.vo2MaxEstimado()));
        sb.append("REGRA: Retorne EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' do cenário identificado no arquivo abaixo. ")
          .append("IMPORTANTE: Dê o feedback de forma amigável e motivadora, tratando o atleta pelo nome ").append(nomeAtleta).append(".\n\n");

        sb.append("--- BASE DE CONHECIMENTO (studySettings) ---\n");
        sb.append(scientificContext).append("\n\n");

        sb.append("--- CONTEXTO HISTÓRICO (MÉDIAS DE 10 TREINOS) ---\n")
                .append(String.format("- VO2 Máx Médio: %.1f ml/kg/min\n", histVo2Medio))
                .append(String.format("- FC Máxima Média: %d bpm\n", (int)histFcMaxMedia))
                .append(String.format("- FC Média Geral: %d bpm\n", (int)histFcMedioDasMedias))
                .append(String.format("- Pace Médio: %s min/km\n", formatSecondsToPace(histPaceMedioSegundos)))
                .append(String.format("- Eficiência Média: %.3f (m/bpm*min)\n\n", histEfficiencyIndex));

        workoutPrescriptionRepository.findByScheduledDate(date.toLocalDate()).ifPresent(plano -> sb.append("--- TREINO PROGRAMADO PARA ESTA DATA ---\n")
                .append("- Tipo Planejado: ").append(plano.getType()).append("\n")
                .append("- Duração/Volume: ").append(plano.getDuration()).append("\n")
                .append("- Intensidade Alvo: ").append(plano.getIntensity()).append("\n")
                .append("- Foco Técnico: ").append(plano.getFocus()).append("\n\n")
                .append("INSTRUÇÃO: Avalie se o atleta cumpriu o plano ou se houve desvio.\n\n"));

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append(String.format("- Duração: %d minutos\n", metrics.duracao()));
        sb.append(String.format("- FC Média: %.0f bpm | Máx: %.0f bpm\n", metrics.fcMedia(), metrics.fcMax()));
        sb.append(String.format("- Zona 2: %.1f%%\n", metrics.z2Percent()));
        sb.append(String.format("- Estabilidade: %s (StdDev: %.1f)\n", metrics.comportamento(), metrics.stdDev()));
        sb.append(String.format("- Altimetria: %.0f m | Pace: %s\n", metrics.ganhoAlt(), metrics.paceFormatted()));
        sb.append(String.format("- Eficiência: %.3f (metros/bpm*min)\n", metrics.efficiencyIndex()));
        sb.append(String.format("- Tipo de Intensidade Detectado: %s\n", workoutIntensityType));
        sb.append("------------------------------\n\n");

        sb.append("TAREFA DE ANÁLISE TÉCNICA E CIENTÍFICA:\n")
                .append("1. ANÁLISE DE INTENSIDADE:\n")
                .append("   - Para ALTA_INTENSIDADE: Foque nos PICOS de Frequência Cardíaca e Ritmo na SÉRIE TEMPORAL. Ignore a média geral, pois ela é diluída pela recuperação.\n")
                .append("   - Para BAIXA_INTENSIDADE: Foque na ESTABILIDADE e na manutenção rigorosa da Zona 2. Use o campo 'comportamento' para identificar drift cardíaco (desacoplamento aeróbico), validando a eficiência mitocondrial conforme San-Millán.\n")
                .append("2. COMPARAÇÃO PLANO VS REAL: Se houver um 'TREINO PROGRAMADO' acima, compare-o rigorosamente com os dados reais. Valide se o atleta respeitou as zonas prescritas ou se houve desvio (ex: picos em dia de Zona 2).\n")
                .append("3. ENQUADRAMENTO CIENTÍFICO: Classifique o impacto metabólico usando a BASE DE CONHECIMENTO (San-Millán, Seiler, Olguín). Explique como os picos ou a estabilidade afetaram a saúde mitocondrial e as vias de sinalização (como NOX2).\n")
                .append("4. FORMATO: Monte o retorno estritamente no formato estruturado abaixo (SEM ASTERISCOS nos títulos e sem blocos de código).\n\n");

        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n").append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [Título]\n");
        sb.append("📊 Métricas: ").append(String.format("%.1f km", metrics.safeDistance())).append(" | ").append(metrics.duracao()).append(" min | FC Méd: ").append((int)metrics.fcMedia())
                .append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante())
                .append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ")
                .append(String.format("%.1f", metrics.vo2MaxEstimado())).append(" | Pace: ").append(metrics.paceFormatted()).append("\n\n");

        sb.append("🩺 DIAGNÓSTICO FISIOLÓGICO PARA ").append(nomeAtleta).append(":\n")
                .append("[Transcreva aqui o texto do campo 'Diagnóstico Clínico-Esportivo da IA' da BASE de conhecimento para o cenário identificado, dirigindo-se amigavelmente a ").append(nomeAtleta).append("]\n\n")

                .append("ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (").append(nomeAtleta).append("):\n")
                .append("[Análise técnica sucinta correlacionando fcMedia, Pace, Altimetria e o cálculo final de efficiencyIndex. Consulte no arquivo studySettings especificamente o tópico '🏷️ Legenda: Índice de Eficiência Aeróbica' para classificar a qualidade e o status do treino em relação à eficiência. Transcreva a Legenda apropriada: Índice de Eficiência Aeróbica do arquivo studySettings]\n\n");

        sb.append("🏃\u200D♂️ CONCLUSÃO E PRÓXIMO PASSO PARA ").append(nomeAtleta).append(":\n")
                .append("[Explique para ").append(nomeAtleta).append(" o impacto na saúde mitocondrial, diferenciando os benefícios conforme o estímulo dado: Alta Intensidade (sinalização hormética e potência) ou Baixa Intensidade (biogênese mitocondrial e eficiência oxidativa). Use as metáforas contidas no estudo e dê um conselho prático final personalizado para ele].\n\n");

        sb.append("--- REPOSIÇÃO E DESCANSO PÓS-TREINO PARA ").append(nomeAtleta).append(" ---\n")
                .append("CONSULTE o CONTEXTO CIENTÍFICO (studySettings) na seção 'DIRETRIZES PARA ALIMENTAÇÃO DE REPOSIÇÃO E DESCANSO'. ")
                .append("Com base nessas diretrizes, forneça recomendações específicas para ").append(nomeAtleta).append(", considerando a intensidade e duração do treino atual.\n")
                .append("[Orientações de reposição e descanso extraídas do CONTEXTO CIENTÍFICO dirigidas a ").append(nomeAtleta).append("]\n\n");

        sb.append("--- PRESCRIÇÃO STRAFIT PREDICT ---\n");
        sb.append("DATA PROGRAMADA: ").append(proximoTreinoData).append("\n\n")
                .append("DIRETRIZES OBRIGATÓRIAS DE PRESCRIÇÃO (ESTRITAMENTE VINCULADAS AO CALENDÁRIO):\n")
                .append("1. REGRA DE OURO (SOBREPÕE QUALQUER ESTUDO): Identifique o dia da semana em 'DATA PROGRAMADA' e ignore outras sugestões de intensidade se o dia não permitir:\n")
                .append("   - Se 'DATA PROGRAMADA' for SÁBADO: Prescreva OBRIGATORIAMENTE um TREINO LONGO em Zona 2. É PROIBIDO prescrever tiros ou alta intensidade no sábado ou terça feira.\n")
                .append("   - Se 'DATA PROGRAMADA' for TERÇA-FEIRA: Prescreva um TREINO CURTO em Zona 2 (Manutenção).\n")
                .append("   - Se 'DATA PROGRAMADA' for QUINTA-FEIRA: Prescreva um TREINO DE INTENSIDADE (TIROS/HIIT).\n\n")
                .append("2. SELEÇÃO DE NÍVEL (Apenas para Quintas): Consulte a MATRIZ DE PROGRESSÃO DE INTENSIDADE no studySettings. ")
                .append("Selecione o NÍVEL adequado (use Nível 1 se não houver intensidade recente no histórico) e calcule os ritmos alvo ")
                .append("garantindo que os tiros sejam significativamente mais rápidos que o 'Ritmo Médio de Corrida' (Pace Médio) de 10 treinos do usuário.\n\n")
                .append("3. FORMATO VISUAL OBRIGATÓRIO (PARA O USUÁRIO):\n")
                .append("Apresente a prescrição obrigatoriamente neste formato de lista antes de qualquer outro texto:\n")
                .append("PRESCRICÃO DE TREINO:\n")
                .append("Tipo: [Tipo do treino]\n")
                .append("Duração: [Tempo ou distância prevista]\n")
                .append("Intensidade: [FC alvo e Zona de esforço]\n")
                .append("Foco: [Objetivo técnico ou biológico]\n")
                .append("Método: [Breve explicação de como executar]\n");

        sb.append("\n--- INSTRUÇÃO TÉCNICA DO SISTEMA ---\n")
                .append("Ao final do relatório, adicione OBRIGATORIAMENTE um bloco estruturado no formato XML abaixo ")
                .append("para processamento automatizado no banco de dados. Preencha os campos com os dados da prescrição criada:\n")
                .append("IMPORTANTE: Não use blocos de código (crases) ao redor do XML. Use apenas texto puro.\n")
                .append("<prescription_data>\n")
                .append("  <scheduled_date>[YYYY-MM-DD]</scheduled_date>\n")
                .append("  <type>[Tipo do treino]</type>\n")
                .append("  <duration>[Duração, ex: 60-75 min]</duration>\n")
                .append("  <intensity>[Zona e FC alvo]</intensity>\n")
                .append("  <focus>[Foco principal do treino]</focus>\n")
                .append("  <method>[Resumo do método em uma linha]</method>\n")
                .append("</prescription_data>\n");

        sb.append(NO_MARKDOWN_INSTRUCTION).append("\n\n");
        sb.append("SERIE TEMPORAL (Min: BPM/Alt/Cad):\n");
        // SÉRIE TEMPORAL (Min: BPM/Alt/Cad):
        // SÉRIE TEMPORAL (Min: BPM/Alt/Cad):

        for (int i = 0; i < analysis.size(); i += 2) {
            StravaActivity.MinuteAnalysis minute = analysis.get(i);

            Double hrObj = minute.getAverageHeartRate();
            Double elevObj = minute.getAverageElevation();
            Double cadObj = minute.getAverageCadence();

            double hrVal = hrObj != null ? hrObj : 0.0;
            double elevVal = elevObj != null ? elevObj : 0.0;
            double cadVal = cadObj != null ? cadObj : 0.0;

            sb.append(String.format("%d:%.0f/%.0fm/%.0f | ",
                    minute.getMinute(),
                    hrVal,
                    elevVal,
                    cadVal));
        }

        sb.append("\n\nPRÓXIMO TREINO: ").append(proximoTreinoData);

        return sb.toString();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void extractAndSavePrescription(Long activityId, String rawAiResponse, Double distanceKm) {
        try {
            String cleanResponse = rawAiResponse.replace("```xml", "").replace("```", "").trim();

            int startTag = cleanResponse.indexOf("<prescription_data>");
            int endTag = cleanResponse.indexOf("</prescription_data>");

            if (startTag != -1 && endTag > startTag) {
                String closeTag = "</prescription_data>";
                String xml = cleanResponse.substring(startTag, endTag + closeTag.length());
                String scheduledDateStr = extractTagValue(xml, "scheduled_date");

                if (scheduledDateStr != null) {
                    log.info("[DB] Processando prescrição vinculada à atividade {} para a data: {}", activityId, scheduledDateStr);

                    // Lógica de UPSERT para Prescrições: Busca por activityId para evitar duplicidade
                    WorkoutPrescriptionEntity prescription = workoutPrescriptionRepository.findByActivityId(activityId)
                            .orElse(new WorkoutPrescriptionEntity());

                    prescription.setActivityId(activityId);
                    prescription.setScheduledDate(LocalDate.parse(scheduledDateStr.replaceAll("[^0-9-]", "")));
                    prescription.setType(extractTagValue(xml, "type"));
                    prescription.setDuration(extractTagValue(xml, "duration"));
                    prescription.setIntensity(extractTagValue(xml, "intensity"));
                    prescription.setFocus(extractTagValue(xml, "focus"));
                    prescription.setDistanceKm(distanceKm);
                    prescription.setPaceTarget(extractTagValue(xml, "method"));

                    WorkoutPrescriptionEntity saved = workoutPrescriptionRepository.saveAndFlush(prescription);
                    log.info("[DB] Prescrição ID {} salva com sucesso para: {}", saved.getId(), saved.getScheduledDate());
                }
            }
        } catch (Exception e) {
            log.error("[ERROR] Falha ao processar XML de prescrição: {}", e.getMessage());
        }
    }

    private String extractTagValue(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = xml.indexOf(open);
        int end = xml.indexOf(close);
        if (start != -1 && end != -1) {
            return xml.substring(start + open.length(), end).trim();
        }
        return null;
    }

    private String determineWorkoutIntensityType(double stdDev, double fcMaxPercentage) {
        if (stdDev > 8.0 && fcMaxPercentage > 90.0) {
            return "ALTA_INTENSIDADE (Intervalado/Picos)";
        } else if (stdDev > 5.0 && fcMaxPercentage > 80.0) {
            return "MÉDIA_INTENSIDADE (Tempo Run/Fartlek)";
        }
        return "BAIXA_INTENSIDADE (Contínuo/Zona 2)";
    }

    private String removeXmlBlock(String text) {
        if (text == null) return "";
        int xmlStart = text.indexOf("<prescription_data>");
        if (xmlStart != -1) {
            return text.substring(0, xmlStart).trim();
        }
        return text;
    }

    private String sanitizeOutput(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#]", "").trim();
    }

    private String formatSecondsToPace(double totalSeconds) {
        if (totalSeconds <= 0) return "N/A";
        long minutes = (long) (totalSeconds / 60);
        long seconds = (long) (totalSeconds % 60);
        return String.format("%d:%02d", minutes, seconds);
    }

    private String formatSpeedToPace(Double speedKmH) {
        if (speedKmH == null || speedKmH == 0) return "N/A";
        double totalSeconds = 3600 / speedKmH;
        return formatSecondsToPace(totalSeconds) + " min/km";
    }

    private String calcularProximaDataTreino(ZonedDateTime date) {
        LocalDate activityDate = date.toLocalDate();
        LocalDate today = LocalDate.now(ZONE_SP);

        LocalDate baseDate = activityDate.isBefore(today) ? today : activityDate;
        LocalDate dataIteracao = baseDate.plusDays(1);

        // Simplificação: Usando Set.of para evitar ambiguidades com EnumSet em alguns compiladores
        Set<DayOfWeek> diasDeTreino = Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);

        while (!diasDeTreino.contains(dataIteracao.getDayOfWeek())) {
            dataIteracao = dataIteracao.plusDays(1);
        }
        return dataIteracao.atStartOfDay(ZONE_SP).format(NEXT_WORKOUT_FORMATTER);
    }

    private ZonedDateTime parseToZonedDateTime(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return ZonedDateTime.now(ZONE_SP);
        }

        try {
            String localPart = dateStr.length() >= 19 ? dateStr.substring(0, 19) : dateStr;
            return LocalDateTime.parse(localPart.replace(" ", "T"), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZONE_SP);
        } catch (Exception e) {
            try {
                log.warn("[DATE] Falha ao parsear data completa, tentando data simples: {}", dateStr);
                return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZONE_SP);
            } catch (Exception ex) {
                try {
                    return ZonedDateTime.parse(dateStr).withZoneSameLocal(ZONE_SP);
                } catch (Exception exc) {
                    return ZonedDateTime.now(ZONE_SP);
                }
            }
        }
    }
}