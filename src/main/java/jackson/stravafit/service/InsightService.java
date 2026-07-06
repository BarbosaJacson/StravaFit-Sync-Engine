package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.StravaActivity;
import jackson.stravafit.model.ActivityEntity;
import jackson.stravafit.repository.ActivityRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.data.domain.Sort;
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
        double vo2MaxEstimado = 0.0;
        if (hrResting > 0 && fcMax > hrResting) {
            vo2MaxEstimado = 15.3 * ((fcMax / hrResting));
        }

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

        String workoutIntensityType = detectarPadraoDeTreino(analysis, metrics);
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

        // Busca a penúltima prescrição para dar contexto de ciclo de treino
        // Lógica aprimorada: Busca a prescrição mais recente ANTERIOR à data do treino atual.
        workoutPrescriptionRepository.findAll().stream()
                .filter(p -> p.getScheduledDate().isBefore(date.toLocalDate()))
                .max(Comparator.comparing(WorkoutPrescriptionEntity::getScheduledDate))
                .ifPresent(penultimatePlano -> {
                    sb.append("--- REFERÊNCIA DO TREINO ANTERIOR PRESCRITO ---\n");
                    sb.append(String.format("- Data: %s\n", penultimatePlano.getScheduledDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
                    sb.append(String.format("- Duração: %s\n", penultimatePlano.getDuration()));
                    sb.append(String.format("- Intensidade (Faixa de FC): %s\n", penultimatePlano.getIntensity()));
                    sb.append(String.format("- Foco: %s\n\n", penultimatePlano.getFocus()));
                    sb.append("INSTRUÇÃO: Use estes dados como referência do ciclo de treino anterior para avaliar a progressão ou fadiga do atleta.\n\n");
                });

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append(String.format("- Duração: %d minutos\n", metrics.duracao()));
        sb.append(String.format("- FC Média: %.0f bpm | Máx: %.0f bpm\n", metrics.fcMedia(), metrics.fcMax()));
        sb.append(String.format("- Zona 2: %.1f%%\n", metrics.z2Percent()));
        sb.append(String.format("- Estabilidade: %s (StdDev: %.1f)\n", metrics.comportamento(), metrics.stdDev()));
        sb.append(String.format("- Altimetria: %.0f m | Pace: %s\n", metrics.ganhoAlt(), metrics.paceFormatted()));
        sb.append(String.format("- Eficiência: %.3f (metros/bpm*min)\n", metrics.efficiencyIndex()));
        sb.append(String.format("- Tipo de Intensidade Detectado: %s\n", workoutIntensityType));
        sb.append("------------------------------\n\n");

        // INSTRUÇÃO DIRETA E IMPERATIVA PARA A IA
        sb.append("TAREFA DE TRANSCRIÇÃO OBRIGATÓRIA:\n")
          .append("1. O tipo de treino já foi definido como: '").append(workoutIntensityType).append("'.\n")
          .append("2. Localize o cenário correspondente a este tipo na 'MATRIZ DE DECISÃO' dentro do arquivo 'studySettings.txt'.\n")
          .append("3. Transcreva EXATAMENTE o texto do campo 'Diagnóstico Clínico-Esportivo da IA' para o cenário encontrado.\n")
          .append("4. Monte a resposta final seguindo o formato estruturado abaixo, preenchendo os campos solicitados.\n\n");

        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n").append(dataFormatada).append("\n");
        sb.append("📌 Cenário Detectado: [Título do Cenário]\n");
        sb.append("⚡ Intensidade do Estímulo: [ANÁLISE CRÍTICA DE INTENSIDADE (REGRA ELIMINATÓRIA)]\n");
        sb.append("📊 Métricas: ").append(String.format("%.1f km", metrics.safeDistance())).append(" | ").append(metrics.duracao()).append(" min | FC Méd: ").append((int)metrics.fcMedia()).append(" min | FC Max: ").append((int)metrics.fcMax())
                .append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante())
                .append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append(" | Pace: ").append(metrics.paceFormatted()).append("\n\n");
        sb.append("\n📊 Histórico Médio (Últimos 10 treinos):\n[Transcreva aqui os dados do bloco 'CONTEXTO HISTÓRICO' em formato de lista, como VO2 Médio, FC Média, etc.]\n\n");

        sb.append("📋 Referência (Treino Anterior Prescrito):\n[Se houver dados no bloco 'REFERÊNCIA DO TREINO ANTERIOR PRESCRITO', transcreva-os aqui de forma resumida, incluindo Data, Duração e Foco.]\n\n");
        sb.append("🩺 DIAGNÓSTICO TÉCNICO FISIOLÓGICO PARA ").append(nomeAtleta).append(":\n")
                .append("\n - [Com base no 'Tipo de Intensidade Detectado' fornecido, busque o cenário correspondente na 'MATRIZ DE DECISÃO' do arquivo 'studySettings.txt' e transcreva integralmente o seu respectivo campo 'Diagnóstico Clínico-Esportivo da IA', adaptando o tratamento para falar de forma amigável e direta com ").append(nomeAtleta).append("]\n\n")

                .append("\n - ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (").append(nomeAtleta).append("):\n")
                .append("\n Transcreva apenas a Legenda identificada correspondente a [Classificação de Intensidade]: Índice de Eficiência Aeróbica do arquivo studySettings no tópico: MATRIZ DE DECISÃO: CENÁRIOS DE COMPARAÇÃO PARA PROCESSAMENTO DA IA\n. ")
                .append("\n  - [STATUS DO TREINO: CUMPRIDO]: Se o atleta executou o tipo de treino correto, respeitou as zonas cardíacas/alvos de ritmo e manteve o tempo de treino dentro de uma margem de tolerância de +/- 10% da duração prescrita.\n")
                .append("\n  - [STATUS DO TREINO:CUMPRIDO PARCIALMENTE]: Se o atleta respeitou o tipo de treino e a intensidade (faixa cardíaca/tiros), mas divergiu na duração por uma margem maior que 10% (abaixo ou acima do volume planejado).\n")
                .append("\n  - [STATUS DO TREINO:NÃO CUMPRIDO]: Se o atleta ignorou completamente a estrutura prescrita (ex: executou uma rodagem contínua em um dia agendado para treinos de tiro, ou treinou em zonas de estresse glicolítico quando a orientação era regenerativa).\n")
                .append("\n  Insira essa classificação de cumprimento de forma clara, justificando fisiologicamente as razões do enquadramento.]\n\n")
                .append("[Análise técnica sucinta correlacionando e analisando as interações entre a zonaPredominante, fcMedia, Pace, Altimetria, efficiencyIndex com o [STATUS DO TREINO]. ")
                .append("Consulte no arquivo studySettings especificamente o tópico '🏷️ Legenda: Índice de Eficiência Aeróbica' para classificar a qualidade e o status do treino em relação à eficiência. ")
                .append("O motor Java fornecerá os dados do treino agendado na tabela 'workout_prescriptions' para a mesma data. ")
                .append("Compare rigidamente o treino executado com o prescrito e classifique o status de cumprimento sob os seguintes critérios técnicos:\n");

        sb.append("🏃\u200D♂️ CONCLUSÃO E PRÓXIMO PASSO PARA ").append(nomeAtleta).append(":\n")
                .append("[Explique para ").append(nomeAtleta).append(" o impacto na saúde mitocondrial, diferenciando os benefícios conforme o estímulo dado: Alta Intensidade (sinalização hormética e potência) ou Baixa Intensidade (biogênese mitocondrial e eficiência oxidativa). Use as metáforas contidas no estudo e dê um conselho prático final personalizado para ele].\n\n");

        sb.append("--- REPOSIÇÃO E DESCANSO PÓS-TREINO PARA ").append(nomeAtleta).append(" ---\n")
                .append("CONSULTE o CONTEXTO CIENTÍFICO (studySettings) na seção 'DIRETRIZES PARA ALIMENTAÇÃO DE REPOSIÇÃO E DESCANSO'. ")
                .append("Com base nessas diretrizes, forneça recomendações específicas para ").append(nomeAtleta).append(", considerando a intensidade e duração do treino atual.\n")
                .append("[Orientações de reposição e descanso extraídas do CONTEXTO CIENTÍFICO dirigidas a ").append(nomeAtleta).append("]\n\n");

        sb.append("--- PRESCRIÇÃO STRAFIT PREDICT ---\n");
        sb.append("DATA PROGRAMADA: ").append(proximoTreinoData).append("\n\n")
                .append("DIRETRIZES OBRIGATÓRIAS DE PRESCRIÇÃO (CONSULTAR MATRIZ DE CONHECIMENTO INCLUÍDA NO ARQUIVO STUDYSETTINGS):\n")
                .append("1. REGRA DE OURO DO CALENDÁRIO: Identifique o dia da semana em 'DATA PROGRAMADA' e prescreva estritamente o tipo de treino correspondente, cruzando com os conceitos do arquivo 'studySettings':\n\n")

                .append("   - 📅 Se 'DATA PROGRAMADA' for SÁBADO:\n")
                .append("     1. 🟢 Prescreva OBRIGATORIAMENTE um TREINO LONGO focado em Volume Puro e Eficiência Mitocondrial na Zona 2 (124 a 138 bpm).\n")
                .append("     2. 🛑 É terminantemente PROIBIDO prescrever tiros, HIIT ou alta intensidade (Zonas 3/4/5) aos sábados ou terças-feiras.\n")
                .append("     3. ⏳ DURAÇÃO POR EFFICIENCY INDEX: Consulte obrigatoriamente a seção 'REGRA DE PROGRESSÃO EM VOLUME LONGO PARA OS SÁBADOS' no arquivo 'studySettings.txt'. Avalie estritamente a média do 'efficiency_index' obtida nos últimos 5 treinos longos da tabela activity_performance_summary e aplique os critérios exatos de corte:\n")
                .append("        - 🔒 TRAVA DE RETENÇÃO (Média entre 0.94 e 1.07): Retenha o atleta obrigatoriamente no tempo base de 60 a 75 minutos.\n")
                .append("        - 📈 PROGRESSÃO GRADUAL (Média >= 1.08): Quebre a trava e progrida a duração para 80 a 85 minutos.\n")
                .append("     4. 🧠 JUSTIFICATIVA BIOLÓGICA: Gere a justificativa clínica de proteção do sistema nervoso autônomo com base nos achados de Stephen Seiler (2010), explicando como a manutenção da rotação no ponto doce do FATmax consolida a capilarização muscular sem gerar fadiga residual.\n\n")

                .append("   - 📅 Se 'DATA PROGRAMADA' for TERÇA-FEIRA:\n")
                .append("     1. 🏃‍♂️ PRESCRIÇÃO E FOCO METABÓLICO: Prescreva um treino curto ou médio em ZONA 2 com foco estrito em oxidação máxima de gordura (FATmax) e clareamento de lactato basal, mantendo os batimentos rigidamente entre 124 a 138 bpm.\n")
                .append("     2. ⚠️ OBRIGATORIEDADE DE CONSULTA E PROGRESSÃO EM VOLUME: Consulte obrigatoriamente a seção 'REGRA DE PROGRESSÃO EM VOLUME CURTO A MÉDIO PARA TERÇAS-FEIRAS (ZONA 2(124 - 138 BPM) - BASE AERÓBICA)' no arquivo 'studySettings.txt'. Avalie a média do 'efficiency_index' dos últimos 5 treinos curtos ou médios do usuário na tabela activity_performance_summary.\n")
                .append("     3. ⏳ REGRAS DE CORTE DE TEMPO:\n")
                .append("        - 🔒 TRAVA DE RETENÇÃO (Média entre 0.94 e 1.04): Mantenha a duração retida entre 45 a 60 minutos para consolidação capilar.\n")
                .append("        - 📈 PROGRESSÃO GRADUAL (Média >= 1.05): Quebre a retenção e expanda o tempo total da sessão para 65 a 75 minutos, justificando que a alta economia de corrida permite expandir o suporte volumétrico celular.\n")
                .append("     4. ⚖️ RECALIBRAÇÃO DE FADIGA RESIDUAL: Caso o 'efficiency_index' médio recente esteja abaixo de 1.05, ou se o último Treino Longo do histórico apresentar desvio (Zona Cinzenta ou Sobrecarga Glicolítica), aborte a progressão. Force o tempo para o patamar mínimo de 45 a 60 minutos para restaurar a flexibilidade metabólica sem gerar desgaste para a quinta-feira.\n\n")

                .append("   - Se 'DATA PROGRAMADA' for QUINTA-FEIRA:\n")
                .append("     1. Prescreva OBRIGATORIAMENTE um TREINO DE INTENSIDADE (TIROS/HIIT).\n\n")

                .append("2. SELEÇÃO DE NÍVEL E PROGRESSÃO (Apenas para Quintas-Feiras):\n")
                .append("   - OBRIGATORIEDADE DE CONSULTA: Consulte obrigatoriamente a 'MATRIZ DE PROGRESSÃO DE INTENSIDADE (HIIT / TIROS - BASE SEILER & CASANOVA) INTEGRAÇÃO CIENTÍFICA: VIA MOLECULAR NOX2 (HENRÍQUEZ-OLGUÍN ET AL., 2019)' contida no arquivo 'studySettings.txt'.\n")
                .append("   - Avalie o histórico recente de tiros do usuário. Se o banco de dados indicar que os últimos 5 treinos intervalados de alta intensidade (Cenário 6) apresentaram um 'efficiency_index' consistentemente estável ou crescente acima de 0.94 (conforme a amostragem atual), quebre a trava de retenção e force a progressão gradual do atleta. Promova-o do 'NÍVEL 1: ADAPTAÇÃO' para o 'NÍVEL 2: TRANSIÇÃO' (ou para o NÍVEL 3 caso o índice supere 1.05 com FC controlada). Use estritamente as metas de volume, tempo de tiro e as faixas de batimento cardíaco alvo de cada nível descritas na Matriz de Progressão para desenhar a nova estrutura da planilha, garantindo que o gatilho da via molecular NOX2 seja agressivo o suficiente para expandir o teto do VO2 máx sem romper os limites de segurança hormética.\n\n")

                .append("3. DIRETRIZ DE EVOLUÇÃO POR ÍNDICE DE EFICIÊNCIA (EFFICIENCY INDEX):\n")
                .append("   - ELIMINAÇÃO DE PACE ESTÁTICO: Não prescreva ritmos/paces fixos (como 3:50/km) como meta isolada, pois eles não refletem o tempo de sustentação do esforço.\n")
                .append("   - AVALIAÇÃO BASEADA NO EFFICIENCY INDEX: Avalie a média do 'efficiency_index' obtida estritamente nos últimos 5 treinos de alta intensidade (Cenário 6).\n")
                .append("   - DIRETRIZ DE MANUTENÇÃO E PROGRESSÃO: Se o 'efficiency_index' médio recente estiver consolidado entre 0.94 e 1.05, a meta para o próximo nível não é correr mais rápido, mas sim MANTER esse mesmo nível de eficiência mecânica/cardiovascular sob o volume expandido do novo nível (ex: sustentar a eficiência migrando de tiros de 1 min para tiros de 2 ou 3 min, conforme a Matriz de Progressão).\n")
                .append("   - ORIENTAÇÃO DE EXECUÇÃO À IA: Instrua o usuário a focar na estabilização do 'efficiency_index' e na resposta da Frequência Cardíaca Alvo do nível estipulado no arquivo 'studySettings.txt' (Nível 2: 150-155 bpm | Nível 3: >155 bpm), explicando que a verdadeira evolução da via NOX2 se dá pelo aumento do tempo sob o estímulo correto, e não pelo ganho de velocidade isolada.\n\n")

                .append("4. FORMATO VISUAL OBRIGATÓRIO (PARA O USUÁRIO):\n")
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

    private String detectarPadraoDeTreino(List<StravaActivity.MinuteAnalysis> analysis, SessionMetrics metrics) {
        final int MIN_PICOS_PARA_INTERVALADO = 4;
        final double PICO_THRESHOLD_BPM = 15.0; // BPM acima da média para ser considerado um pico

        int picosDetectados = 0;
        boolean emPico = false;

        for (StravaActivity.MinuteAnalysis minuto : analysis) {
            Double hr = minuto.getAverageHeartRate();
            if (hr == null) continue;

            if (hr > metrics.fcMedia() + PICO_THRESHOLD_BPM && !emPico) {
                emPico = true;
                picosDetectados++;
            } else if (hr < metrics.fcMedia() + (PICO_THRESHOLD_BPM / 2) && emPico) {
                // Considera que saiu do pico se a FC cair para menos da metade do threshold
                emPico = false;
            }
        }

        if (picosDetectados >= MIN_PICOS_PARA_INTERVALADO) {
            return "ALTA_INTENSIDADE (Intervalado)";
        } else if (metrics.stdDev() > 7.0 && metrics.fcMaxPercentage() > 85.0) { // Ajustado para ser mais seletivo
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