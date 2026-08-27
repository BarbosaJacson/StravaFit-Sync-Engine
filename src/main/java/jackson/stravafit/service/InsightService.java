package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.model.*;
import jackson.stravafit.repository.ActivityRepository;
import jackson.stravafit.repository.ActivitySummaryRepository;
import jackson.stravafit.repository.UserRepository;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
@Service
public class InsightService {

    private final GeminiClient geminiClient;
    private final ActivityRepository activityRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final ActivityService activityService;
    private final ActivitySummaryRepository activitySummaryRepository;
    private final UserRepository userRepository;
    private final KnowledgeService knowledgeService;
    private final InsightService self;
    private final OpenMeteoService openMeteoService;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter BRAZIL_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter NEXT_WORKOUT_FORMATTER = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy");

    @Autowired
    public InsightService(GeminiClient geminiClient,
                          ActivityRepository activityRepository,
                          WorkoutPrescriptionRepository workoutPrescriptionRepository,
                          ActivitySummaryRepository activitySummaryRepository,
                          UserRepository userRepository,
                          ActivityService activityService,
                          @Lazy InsightService self,
                          KnowledgeService knowledgeService, OpenMeteoService openMeteoService) {
        this.geminiClient = geminiClient;
        this.activityRepository = activityRepository;
        this.workoutPrescriptionRepository = workoutPrescriptionRepository;
        this.activitySummaryRepository = activitySummaryRepository;
        this.userRepository = userRepository;
        this.activityService = activityService;
        this.self = self;
        this.knowledgeService = knowledgeService;
        this.openMeteoService = openMeteoService;
    }

    public record ClassificacaoResultado(String tipoEstimulo, int janelasInstaveis) {
    }

    @Transactional
    public String getActivityInsight(StravaActivity activity, List<StravaActivity.MinuteAnalysis> analysis) {

        // 1. Identifica o usuário
        Long athleteIdStrava = (activity != null && activity.getAthlete() != null) ? activity.getAthlete().getId() : null;
        UserEntity user = Optional.ofNullable(athleteIdStrava)
                .flatMap(userRepository::findByStravaAthleteId)
                .orElseGet(() -> userRepository.findById(1L)
                        .orElseThrow(() -> new IllegalStateException("Atleta principal (ID 1) não cadastrado no MySQL.")));

        Double lat = activity != null ? activity.getLatitude() : null;
        Double lng = activity != null ? activity.getLongitude() : null;

// Converte a String de data do Strava para LocalDateTime tratando o formato ISO
        LocalDateTime startDate = null;
        if (activity != null && activity.getStartDateLocal() != null) {
            try {
                String dateStr = activity.getStartDateLocal().replace("Z", "");
                startDate = LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception e) {
                log.warn("[CLIMA] Falha ao converter data da atividade: {}", e.getMessage());
            }
        }

        WeatherData weather = openMeteoService.getWeatherForLocation(lat, lng, startDate);

        String climaHeader = (weather != null && weather.hasData())
                ? weather.toTelegramFormat()
                : "🌤️ CLIMA DURANTE O TREINO: Dados indisponíveis";

        // 🎯 3. Executa o generateInsight passando TODOS os dados necessários!
        // Ele processa os cálculos e a chamada ao Gemini, devolvendo o texto do parecer.
        String insightDaIA = generateInsight(
                user,
                activity.getId(),
                activity.getName(),
                activity.getDistance() != null ? activity.getDistance() / 1000.0 : 0.0,
                activity.getStartDateLocal(),
                activity.getAverageSpeed() != null ? activity.getAverageSpeed() * 3.6 : 0.0,
                analysis,
                activity,
                climaHeader);

        // 🎯 4. Junta a linha do clima no topo com o texto retornado pela IA e entrega o resultado final!
        return insightDaIA;
    }

    private String generateInsight(UserEntity user, Long activityId, String name, Double distance, String dateStr, Double averageSpeed, List<StravaActivity.MinuteAnalysis> analysis, StravaActivity activity, String climaHeader) {

        // 1. Primeiro calculamos as métricas essenciais e as datas baseadas nas preferências do atleta
        ZonedDateTime activityDate = parseToZonedDateTime(dateStr);
        Set<DayOfWeek> diasConfigurados = parseTrainingDays(user.getTrainingDays());
        String proximoTreinoData = calcularProximaDataTreino(activityDate, diasConfigurados);
        SessionMetrics metrics = calcularMetricasSessao(analysis, distance, averageSpeed, user);
        Optional<WorkoutPrescriptionEntity> prescricaoAnterior =
                workoutPrescriptionRepository.findTopByScheduledDateLessThanEqualOrderByScheduledDateDescCreatedAtDesc(activityDate.toLocalDate());
        // 2. Classificamos o estímulo para saber qual cenário a sessão atual pertence
        ClassificacaoResultado resultado = classificarEstimuloFisiologico(analysis, metrics.stdDev(), metrics.fcMax(), metrics.fcMedia());
        String tipoEstimuloReal = resultado.tipoEstimulo();
        int janelasInstaveis = resultado.janelasInstaveis();
        boolean ehTiro = tipoEstimuloReal.contains("TIROS");

        // 3. Criamos a entidade da sessão atual em memória para garantir inclusão imediata no histórico do prompt
        ActivitySummaryEntity treinoAtualVirtual = new ActivitySummaryEntity();
        treinoAtualVirtual.setActivityId(activityId);
        treinoAtualVirtual.setStartDate(activityDate.toLocalDateTime());
        treinoAtualVirtual.setDistanceKm(metrics.safeDistance());
        treinoAtualVirtual.setTotalTimeMinutes(metrics.duracao());
        treinoAtualVirtual.setAverageHeartRate(metrics.fcMedia());
        treinoAtualVirtual.setEfficiencyIndex(metrics.efficiencyIndex());

        // 4. Buscamos os históricos existentes no MySQL por Cenário
        List<ActivitySummaryEntity> listaTirosOriginal = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(2);

        List<ActivitySummaryEntity> listaCenario1Original = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

        // 5. Injetamos a atividade atual na memória limpando duplicatas de data ou ID
        LocalDate dataHoje = activityDate.toLocalDate();

        List<ActivitySummaryEntity> listaTiros = listaTirosOriginal.stream()
                .filter(a -> a.getStartDate() == null || !a.getStartDate().toLocalDate().equals(dataHoje))
                .filter(a -> a.getActivityId() == null || !a.getActivityId().equals(activityId))
                .collect(Collectors.toList());

        List<ActivitySummaryEntity> listaCenario1 = listaCenario1Original.stream()
                .filter(a -> a.getStartDate() == null || !a.getStartDate().toLocalDate().equals(dataHoje))
                .filter(a -> a.getActivityId() == null || !a.getActivityId().equals(activityId))
                .collect(Collectors.toList());

        // Busca no MySQL a prescrição agendada para o próximo treino (Terça, Quinta ou Sábado)
        LocalDate dataProximo = parseNextWorkoutDate(proximoTreinoData);
        Optional<WorkoutPrescriptionEntity> proximaPrescricao = workoutPrescriptionRepository.findByScheduledDate(dataProximo);

        // Busca o último nível gravado no MySQL pela WeeklyPlannerService para cada cenário
        int nivelAtualCenario1 = workoutPrescriptionRepository
                .findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(1)
                .map(WorkoutPrescriptionEntity::getTargetLevel)
                .orElse(1);

        int nivelAtualCenario2 = workoutPrescriptionRepository
                .findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(2)
                .map(WorkoutPrescriptionEntity::getTargetLevel)
                .orElse(1);

        // Adiciona a sessão virtual única no topo da lista correspondente
        if (ehTiro) {
            listaTiros.add(0, treinoAtualVirtual);
            listaTiros.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());
        } else {
            listaCenario1.add(0, treinoAtualVirtual);
            listaCenario1.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());
        }

        // 6. Separação precisa por dia da semana na memória
        List<ActivitySummaryEntity> listaTercas = listaCenario1.stream()
                .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.TUESDAY)
                .toList();

        List<ActivitySummaryEntity> listaSabados = listaCenario1.stream()
                .filter(a -> a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .toList();

        // 7. Cálculo das médias de eficiência incluindo o treino de hoje
        double mediaEficienciaZ2Curto = calcularMediaEficienciaDaLista(listaTercas, 5);
        double mediaEficienciaZ2Longo = calcularMediaEficienciaDaLista(listaSabados, 5);
        double mediaEficienciaTiros = calcularMediaEficienciaDaLista(listaTiros, 5);

        // 8. Busca no MySQL a prescrição agendada para a data do treino de hoje
        LocalDate dataTreinoHoje = activityDate.toLocalDate();
        Optional<WorkoutPrescriptionEntity> prescricaoHoje = workoutPrescriptionRepository.findByScheduledDate(dataTreinoHoje);

        // 9. Mapeia o cenário e o nível salvando o histórico real
        int cenarioDetectado = ehTiro ? 2 : 1;
        int nivelDetectado = prescricaoHoje.map(WorkoutPrescriptionEntity::getTargetLevel).orElse(1);

        // 10. LINHA DO TEMPO CRONOLÓGICA GLOBAL
        List<ActivitySummaryEntity> historicoGlobalFiltrado = new ArrayList<>();
        historicoGlobalFiltrado.addAll(listaTiros);
        historicoGlobalFiltrado.addAll(listaCenario1);

        historicoGlobalFiltrado.sort(Comparator.comparing(ActivitySummaryEntity::getStartDate).reversed());

        StringBuilder sbHistoricoGlobal = new StringBuilder();
        for (ActivitySummaryEntity act : historicoGlobalFiltrado.stream().limit(9).toList()) {
            String tipoTreino = (act.getDetectedScenario() != null && act.getDetectedScenario() == 2) ? "TIROS" : "Z2 / RODAGEM";
            double ef = (act.getEfficiencyIndex() != null) ? act.getEfficiencyIndex() : metrics.efficiencyIndex();
            double fc = (act.getAverageHeartRate() != null) ? act.getAverageHeartRate() : metrics.fcMedia();

            sbHistoricoGlobal.append(String.format("• Data: %s (%s) | Efficiency: %.3f | FC Méd: %.0f bpm\n",
                    act.getStartDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    tipoTreino,
                    ef,
                    fc));

            if (act.getAiAnalysisSummary() != null && !act.getAiAnalysisSummary().isBlank()) {
                sbHistoricoGlobal.append("  [Diagnóstico Anterior]: ")
                        .append(act.getAiAnalysisSummary().replaceAll("\n", " "))
                        .append("\n");
            }
            sbHistoricoGlobal.append("\n");
        }

        String historicoPerformanceGlobal = sbHistoricoGlobal.toString();

        // 11. Passamos o prompt estruturado com todas as variáveis instanciadas e o 'user'
        String prompt = buildProfessionalPrompt(user, name, metrics, activityDate, proximoTreinoData,
                prescricaoAnterior.orElse(null), proximaPrescricao.orElse(null),
                nivelAtualCenario1, nivelAtualCenario2, tipoEstimuloReal, janelasInstaveis,
                mediaEficienciaTiros, mediaEficienciaZ2Curto, mediaEficienciaZ2Longo,
                historicoPerformanceGlobal, climaHeader);

        String rawAiResponse = geminiClient.getInsight(prompt);
        String cleanResult = removeXmlBlock(rawAiResponse);


        // 12. Persiste os dados técnicos calculados oficialmente no MySQL
        self.persistirDadosTecnicos(activityId, user.getId(), activityDate, metrics, cleanResult, rawAiResponse, tipoEstimuloReal, cenarioDetectado, nivelDetectado);
        return sanitizeOutput(cleanResult);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistirDadosTecnicos(Long activityId, Long userId, ZonedDateTime activityDate, SessionMetrics metrics,
                                       String cleanResult, String rawAiResponse,
                                       String tipoEstimuloReal, int cenarioDetectado, int nivelDetectado) {
        try {
            // 🎯 1. Busca por activityId diretamente para garantir que faremos UPDATE e não INSERT duplicado
            ActivitySummaryEntity summary = activitySummaryRepository.findByActivityId(activityId)
                    .orElseGet(() -> activitySummaryRepository.findById(activityId)
                            .orElse(new ActivitySummaryEntity()));

            summary.setActivityId(activityId);
            summary.setStartDate(activityDate.toLocalDateTime());
            summary.setDistanceKm(metrics.safeDistance());
            summary.setTotalTimeMinutes(metrics.duracao());
            summary.setAverageHeartRate(metrics.fcMedia());
            summary.setMaxHeartRate((int) metrics.fcMax());
            summary.setDominantZone(metrics.zonaPredominante());
            summary.setEfficiencyIndex(metrics.efficiencyIndex());
            summary.setAiAnalysisSummary(sanitizeOutput(cleanResult));
            summary.setRealStimulusType(tipoEstimuloReal);
            summary.setDetectedScenario(cenarioDetectado);
            summary.setDetectedLevel(nivelDetectado);

            activitySummaryRepository.saveAndFlush(summary);
            log.info("[DB] Sumário de performance persistido/atualizado com sucesso para atividade: {}", activityId);

        } catch (Exception e) {
            log.error("[DB] Falha ao persistir dados técnicos para a atividade {}: {}", activityId, e.getMessage());
        }
    }


    private ClassificacaoResultado classificarEstimuloFisiologico(List<StravaActivity.MinuteAnalysis> analysis, double stdDevGlobal, double fcMax, double fcMedia) {
        if (analysis == null || analysis.size() < 5) {
            return new ClassificacaoResultado("NÃO IDENTIFICADO (DADOS INSUFICIENTES)", 0);
        }

        int picosIntervalados = 0;
        int tamanhoJanela = 3;

        List<Double> frequencias = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .toList();

        if (frequencias.size() < tamanhoJanela) {
            return new ClassificacaoResultado("CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)", 0);
        }

        for (int i = 0; i <= frequencias.size() - tamanhoJanela; i++) {
            List<Double> subLista = frequencias.subList(i, i + tamanhoJanela);

            double mediaJanela = subLista.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double varianciaJanela = subLista.stream()
                    .mapToDouble(fc -> Math.pow(fc - mediaJanela, 2))
                    .average()
                    .orElse(0.0);

            if (Math.sqrt(varianciaJanela) >= 8.0) {
                picosIntervalados++;
            }
        }

        double amplitudeCardiaca = fcMax - fcMedia;

        log.info("[CLASSIFICADOR] Picos instáveis (>8bpm): {} | Amplitude (Max - Média): {} bpm | StdDev Global: {} bpm",
                picosIntervalados,
                String.format("%.1f", amplitudeCardiaca),
                String.format("%.1f", stdDevGlobal));

        if (picosIntervalados >= 6 || (amplitudeCardiaca >= 20.0 && picosIntervalados >= 3)) {
            return new ClassificacaoResultado("INTENSO / INTERVALADO (TIROS)", picosIntervalados);
        }

        return new ClassificacaoResultado("CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)", picosIntervalados);
    }

    private String buildProfessionalPrompt(UserEntity user, String name, SessionMetrics metrics, ZonedDateTime date, String proximoTreinoData,
                                           WorkoutPrescriptionEntity prescricaoAnterior,
                                           WorkoutPrescriptionEntity proximaPrescricao,
                                           int nivelAtualCenario1,
                                           int nivelAtualCenario2,
                                           String tipoEstimuloReal,
                                           int picosIntervalados,
                                           double mediaEficienciaTiros,
                                           double mediaEficienciaZ2Curto,
                                           double mediaEficienciaZ2Longo,
                                           String historicoPerformanceGlobal,
                                           String climaHeader) {
        String dataFormatada = date.format(BRAZIL_FORMATTER);
        String scientificContext = knowledgeService.getScientificContext(user.getGender());
        List<ActivityEntity> historico = activityRepository.findTop10ByOrderByStartDateDesc();

        int hrMax = user.getHrMax();
        int hrRest = user.getHrResting();
        String nomeAtletaReal = (user.getName() != null && !user.getName().isBlank()) ? user.getName() : "Atleta";
        boolean proximoEhSabado = proximoTreinoData.toUpperCase().contains("SÁBADO") || proximoTreinoData.toUpperCase().contains("SATURDAY");

        double histVo2Medio = historico.stream().mapToDouble(a -> 15.3 * ((a.getMaxHeartRate() != null ? a.getMaxHeartRate() : hrMax) / (double) hrRest)).average().orElse(0.0);
        double histFcMaxMedia = historico.stream().mapToDouble(a -> a.getMaxHeartRate() != null ? a.getMaxHeartRate() : 0).average().orElse(0.0);
        double histFcMediaGeral = historico.stream().mapToDouble(a -> a.getAverageHeartRate() != null ? a.getAverageHeartRate() : 0).average().orElse(0.0);
        double histPaceMedioSegundos = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getTotalTimeMinutes() * 60.0) / a.getDistanceKm()).average().orElse(0.0);
        double histEfficiencyIndex = historico.stream().filter(a -> a.getDistanceKm() != null && a.getDistanceKm() > 0 && a.getAverageHeartRate() != null && a.getAverageHeartRate() > 0 && a.getTotalTimeMinutes() != null && a.getTotalTimeMinutes() > 0).mapToDouble(a -> (a.getDistanceKm() * 1000) / (a.getAverageHeartRate() * a.getTotalTimeMinutes())).average().orElse(0.0);

        StringBuilder sb = new StringBuilder();

        sb.append("VOCÊ É O FISIOLOGISTA E TREINADOR CHEFE DO PROJETO STRAVAFIT.\n");
        sb.append("SUA MISSÃO É ANALISAR O TREINO ATUAL, EMITIR UM DIAGNÓSTICO FISIOLÓGICO, PRESCREVER A RECUPERAÇÃO E GERAR O PRÓXIMO TREINO.\n\n");

        sb.append("🚨 REGRA MANDATÓRIA DE LINGUAGEM E TOM DE VOZ:\n");
        sb.append("--- TOM DE VOZ E ESTILO DE COMUNICAÇÃO ---\n");
        sb.append("- PERSONA: Você é um treinador de corrida parceiro, amigável e especialista em fisiologia. O tom deve ser leve, motivador, humano e descontraído (como uma conversa no WhatsApp pós-treino).\n");
        sb.append("- TRATAMENTO: Fale diretamente com o atleta (").append(nomeAtletaReal).append(") usando 'você'. Use apenas o PRIMEIRO NOME de forma amigável (ex: 'Fala, ").append(nomeAtletaReal).append("!', 'Show de bola, ").append(nomeAtletaReal).append("'). NUNCA use o nome completo em todas as frases.\n");
        sb.append("- LINGUAGEM NATURAL: Seja fluido e variado. É PROIBIDO iniciar várias frases seguidas com 'Eu' (ex: PROIBIDO usar 'Eu observei...', 'Eu vejo...', 'Eu classifiquei...').\n");
        sb.append("- PROIBIDO TERCEIRA PESSOA: Nunca fale 'o atleta', 'o corredor' ou 'ele'. Fale direto com ELE de forma pessoal.\n\n");

        sb.append("--- EXEMPLOS DE LINGUAGEM ESPERADA ---\n");

        sb.append("✅ JEITO CERTO (Natural, parceiro e descontraído):\n");
        sb.append("  'Fala, ").append(nomeAtletaReal).append("! Beleza? Cara, que treino sensacional você entregou hoje! Fechar os 10.1 km cravando 95% do tempo na Zona 2 mostra um controle de ritmo absurdo.\n");
        sb.append("  Seu Efficiency Index bateu 1.117, o que é uma marca excelente. Na prática, seu corpo tá ficando cada vez mais eficiente em queimar gordura como combustível (massa demais pra preparação da Meia Maratona!). O coração trabalhou super estável, mesmo com essa garoa e umidade alta...'\n\n");

        sb.append("REGRA DE FORMATAÇÃO: GERE A RESPOSTA USANDO APENAS TEXTO PURO, TÍTULOS EM MAIÚSCULAS E QUEBRAS DE LINHA. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN.\n\n");
        sb.append("--- CONDIÇÕES CLIMÁTICAS NO MOMENTO DO TREINO ---\n");

        sb.append("INSTRUÇÃO FISIOLÓGICA: Considere o estresse térmico acima na análise. Se a umidade for alta (>80%) ou a temperatura for elevada (>25°C), mencione o impacto no aumento da Frequência Cardíaca (débito cardíaco) para termorregulação e dê recomendações de hidratação e recuperação.\n\n");

        sb.append("--- CLASSIFICAÇÃO FISIOLÓGICA REAL DA ATIVIDADE (CÁLCULO MATEMÁTICO DO SISTEMA) ---\n");
        sb.append("- TIPO DE ESTÍMULO EXECUTADO HOJE: ").append(tipoEstimuloReal).append("\n");
        sb.append("- DESVIO PADRÃO DA FC DO TREINO: ").append(String.format("%.2f", metrics.stdDev())).append(" bpm\n");
        sb.append("- PICOS INTERVALADOS BPM (3m, Desvio >= 8.0 bpm): ").append(picosIntervalados).append(" disparos identificados\n");
        sb.append("- INSTRUÇÃO DE CENÁRIO: Se o estímulo foi 'INTENSO / INTERVALADO (TIROS)', acione o 'CENÁRIO 2'. Se foi 'CONTÍNUO / ESTÁVEL', acione o 'CENÁRIO 1'.\n\n");

        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO CIENTÍFICO E DIRETRIZES DO MONGODB ---\n");
            sb.append(scientificContext).append("\n\n");
        }

        sb.append("--- CONTEXTO HISTÓRICO DE LEITURA INTERNA (ÚLTIMAS ATIVIDADES GLOBAIS) ---\n");
        sb.append("ATENÇÃO IA: Use o histórico abaixo APENAS como base de conhecimento interna para avaliar a evolução na Seção 4.0. É PROIBIDO IMPRIMIR ESTE BLOCO NO TELEGRAM:\n");
        sb.append(historicoPerformanceGlobal).append("\n\n");

        sb.append("--- DADOS DO TREINO ATUAL ---\n");
        sb.append("DADOS DO TREINO: ").append(name).append(" | ").append(String.format("%.1f km", metrics.safeDistance())).append(" | Pace Médio: ").append(metrics.paceFormatted()).append("\n");
        sb.append("DURACAO: ").append(metrics.duracao()).append(" min | FC Méd: ").append(String.format("%.0f", metrics.fcMedia())).append(" bpm | FC Max: ").append(String.format("%.0f", metrics.fcMax())).append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante()).append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append("\n");
        sb.append("DESVIO PADRÃO DA FC: ").append(String.format("%.1f", metrics.stdDev())).append(" bpm | COMPORTAMENTO CARDÍACO: ").append(metrics.comportamento()).append("\n\n");

        sb.append("--- NÍVEIS DE PROGRESSÃO ATUAIS DO ATLETA (DEFINIDOS NO PLANEJAMENTO SEMANAL) ---\n");
        sb.append("- Nível Atual no Cenário 1 (Rodagens/Longão): NÍVEL ").append(nivelAtualCenario1).append("\n");
        sb.append("- Nível Atual no Cenário 2 (Tiros de Quinta): NÍVEL ").append(nivelAtualCenario2).append("\n");
        sb.append("REGRA INVIOLÁVEL: Você está ESTRITAMENTE PROIBIDA de recalcular, alterar, promover ou rebaixar o nível determinado acima.\n\n");

        sb.append("--- DADOS DE PRESCRIÇÃO E DIRETRIZES DO MONGODB (MANDATÓRIO) ---\n");
        sb.append("Próximo Treino Agendado: ").append(proximoTreinoData.toUpperCase()).append("\n");
        sb.append("📌 REGRAS DE PRESCRIÇÃO POR DIA DA SEMANA:\n")
                .append("- TERÇA-FEIRA: Obrigatoriamente prescrição de CENÁRIO 1 (Rodagem Leve/Desenvolvimento em Zona 2 - FATMAX).\n")
                .append("- QUINTA-FEIRA: Obrigatoriamente prescrição de CENÁRIO 2 (Intensificação / Tiros / VO2máx - Z3/Z4).\n")
                .append("- SÁBADO/DOMINGO: Obrigatoriamente prescrição de CENÁRIO 1 (Longão / Rodagem de Base - Zona 2).\n\n");
        sb.append("Média Real Tiros (Quintas): ").append(String.format("%.3f", mediaEficienciaTiros)).append("\n");
        sb.append("Média Real Rodagem (Terças): ").append(String.format("%.3f", mediaEficienciaZ2Curto)).append("\n");
        sb.append("Média Real Longão (Sábados): ").append(String.format("%.3f", mediaEficienciaZ2Longo)).append("\n\n");

        sb.append("=================================================================\n");
        sb.append("🚨 DIRECTIVE DE SAÍDA EXCLUSIVA (ESTRITO CUMPRIMENTO MANDATÓRIO) 🚨\n");
        sb.append("=================================================================\n");
        sb.append("ATENÇÃO IA: IGNORE A IMPRESSÃO DE TUDO O QUE FOI LIDO ACIMA.\n");
        sb.append("Sua resposta para o usuário/Telegram DEVE COMEÇAR EXATAMENTE na linha '🏃‍♂️ StravaFit IA...'.\n");
        sb.append("É ESTRITAMENTE PROIBIDO incluir logs, historicosUnificados ou historicoPerformanceGlobal na resposta.\n");
        sb.append("RENDERIZE APENAS A ESTRUTURA A SEGUIR:\n\n");

        sb.append("🏃‍♂️ StravaFit IA - Análise de Eficiência Metabólica\n\n");
        sb.append(dataFormatada).append("\n");
        sb.append("🌤️ CLIMA: ").append(climaHeader).append("\n\n");
        sb.append("📌 CENÁRIO: [Título Exato do Cenário do MongoDB]\n\n");
        sb.append("⚡ INTENSIDADE: [Mapear Intensidade baseada na Zona Predominante] | Estabilidade Fisiológica: ").append(String.format("%.1f", metrics.stdDev())).append(" bpm (Desvio Padrão)\n\n");
        sb.append("📊 MÉTRICAS: ").append(String.format("%.1f km", metrics.safeDistance())).append(" | ").append(metrics.duracao()).append(" min | FC Méd: ").append(String.format("%.0f", metrics.fcMedia())).append(" bpm | FC Max: ").append(String.format("%.0f", metrics.fcMax())).append(" bpm | Zona Pred: Z").append(metrics.zonaPredominante()).append(" | Efic: ").append(String.format("%.3f", metrics.efficiencyIndex())).append(" | VO2: ").append(String.format("%.1f", metrics.vo2MaxEstimado())).append(" | Pace: ").append(metrics.paceFormatted()).append("\n\n");

        sb.append("📊 ESFORÇO POR ZONA CARDÍACA:\n");
        java.util.Map<Integer, Integer> minBpms = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> maxBpms = new java.util.HashMap<>();
        for (int bpm = hrRest; bpm <= hrMax; bpm++) {
            int z = activityService.calculateKarvonenZone(bpm, hrMax, hrRest);
            if (z > 0) {
                minBpms.putIfAbsent(z, bpm);
                maxBpms.put(z, bpm);
            }
        }
        Map<Integer, Double> zonePercentages = metrics.zonePercentages();
        for (int zona = 1; zona <= 5; zona++) {
            double pct = zonePercentages.getOrDefault(zona, 0.0);
            int bpmMin = minBpms.getOrDefault(zona, 0);
            int bpmMax = maxBpms.getOrDefault(zona, 0);

            String descricaoZona = switch (zona) {
                case 1 -> String.format("Z-1 (Ativa / Recuperação) [%d - %d bpm]", bpmMin, bpmMax);
                case 2 -> String.format("Z-2 (Aeróbica / FatMax) [%d - %d bpm]", bpmMin, bpmMax);
                case 3 -> String.format("Z-3 (Tempo / Ritmo) [%d - %d bpm]", bpmMin, bpmMax);
                case 4 -> String.format("Z-4 (Limiar Anaeróbico) [%d - %d bpm]", bpmMin, bpmMax);
                case 5 -> String.format("Z-5 (Capacidade Anaeróbica / Pico) [%d - %d bpm]", bpmMin, bpmMax);
                default -> "Zona " + zona;
            };
            sb.append(String.format("  - %s: %.1f%%\n", descricaoZona, pct));
        }
        sb.append("\n\n");

        sb.append("📈 MÉDIAS DE EFICIÊNCIA ACUMULADAS (5 treinos):\n");
        sb.append("• Rodagem Curta (Terça): ").append(String.format("%.3f", mediaEficienciaZ2Curto)).append(" | Nível ").append(nivelAtualCenario1).append("\n");
        sb.append("• Tiros / VO2máx (Quinta): ").append(String.format("%.3f", mediaEficienciaTiros)).append(" | Nível ").append(nivelAtualCenario2).append("\n");
        sb.append("• Longão (Sábado): ").append(String.format("%.3f", mediaEficienciaZ2Longo)).append(" | Nível ").append(nivelAtualCenario1).append("\n");

        sb.append("📊 HISTÓRICO MÉDIO (10 treinos):\n");
        sb.append("- VO2 Máx Médio: ").append(String.format("%.1f", histVo2Medio)).append(" ml/kg/min\n");
        sb.append("- FC Máxima Média: ").append(String.format("%.0f", histFcMaxMedia)).append(" bpm\n");
        sb.append("- FC Média Geral: ").append(String.format("%.0f", histFcMediaGeral)).append(" bpm\n");
        sb.append("- Pace Médio: ").append(formatSecondsToPace(histPaceMedioSegundos)).append(" min/km\n");
        sb.append("- Eficiência Média: ").append(String.format("%.3f", histEfficiencyIndex)).append(" (m/bpm*min)\n\n");

        if (prescricaoAnterior != null) {
            sb.append("📋 PLANO DO TREINO:\n").append(prescricaoAnterior.getScheduledDate()).append("\n");
            sb.append("- Tipo Planejado: ").append(prescricaoAnterior.getType()).append("\n");
            sb.append("- Duração/Volume: ").append(prescricaoAnterior.getDuration()).append("\n");
            sb.append("- Intensidade Alvo: ").append(prescricaoAnterior.getIntensity()).append("\n");
            sb.append("- Foco Técnico: ").append(prescricaoAnterior.getFocus()).append("\n\n");
        }

        String dataTreinoFormatada = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        sb.append("1.0 - 📋 CUMPRIMENTO DO PLANO PARA O TREINO DE ").append(dataTreinoFormatada).append(":\n");
        sb.append("STATUS: [CUMPRIDO | CUMPRIDO PARCIALMENTE | NÃO CUMPRIDO]\n");
        sb.append("[Escreva em texto corrido a justificativa técnica do cumprimento ou desvio do plano baseando-se na prescrição fornecida. Use o nome do atleta (")
                .append(nomeAtletaReal)
                .append(") no texto de forma amigável e próxima. ");
        sb.append("⚠️ INSTRUÇÃO SOBRE AS DATAS:\n")
                .append("- Compare a data do 'PLANO DO TREINO' com a data em que o treino foi efetivamente realizado.\n")
                .append("- Se as datas forem iguais, siga a análise normalmente sem necessidade de se aprofundar no agendamento.\n")
                .append("- Se o treino foi realizado antes ou depois da data agendada, apenas comente brevemente sobre o impacto no descanso necessário e na prescrição dos próximos treinos.\n\n");

        sb.append("2.0 - 👨‍⚕️ DIAGNÓSTICO TÉCNICO FISIOLÓGICO");
        sb.append("[FOCO EXCLUSIVO: BIOQUÍMICA E CÉLULA]\n");
        sb.append("• Classifique o Efficiency Index de hoje (")
                .append(String.format("%.3f", metrics.efficiencyIndex()))
                .append(") Se dirija ao atleta pelo nome, e compare o EfficiencyIndex com a tabela 'legendas_eficiencia_gerais' presente no contexto científico (scientificContext). Imprima EXATAMENTE e APENAS o texto da linha/legenda correspondente (com o emoji(bolinha colorida) e intervalo).\n");
        sb.append("• Desenvolva a análise metabólica focando EXCLUSIVAMENTE nas vias energéticas (FatMax, oxidação lipídica, biogênese mitocondrial PGC-1alpha, preservação de glicogênio e depuração de lactato).\n");
        sb.append("• Cite formalmente os autores do MongoDB (Casanova et al., San-Millán & Brooks, Seiler).\n");
        sb.append("• É ESTRITAMENTE PROIBIDO citar desvio padrão em bpm, picos intervalados ou oscilação de ritmo nesta seção.\n\n");

        sb.append("3.0 - 🫀 ANÁLISE DE RITMO E COMPORTAMENTO CARDÍACO (");
        sb.append("[FOCO EXCLUSIVO: DINÂMICA TEMPORAL, CARDÍACA, PACE DRIFT E EFICIÊNCIA]\n");
        sb.append("• Use o nome do atleta e desenvolva a análise focando na estabilidade do ritmo e na curva da Frequência Cardíaca ao longo do tempo.\n");
        sb.append("• ANÁLISE MULTIFATORIAL DE IMPACTO (CLIMA E ALTIMETRIA):\n");
        sb.append("  - Dados Climáticos: ").append(climaHeader).append("\n");
        // Substituir a linha da elevação por:
        sb.append("  - Elevação x Efficiency Index: O Efficiency Index (")
                .append(String.format("%.3f", metrics.efficiencyIndex()))
                .append(") já contempla a variação de ")
                .append(String.format("%.0f", metrics.ganhoAlt()))
                .append(" metros de ganho altimétrico\n");
        sb.append("• Justifique a estabilidade utilizando o Desvio Padrão de ").append(String.format("%.1f", metrics.stdDev())).append(" bpm e as ").append(picosIntervalados).append(" picos intervalados identificadas pelo sistema.\n");
        sb.append("• Avalie a presença ou ausência de Pace Drift (desacoplamento cardiovascular) entre a primeira e a segunda metade do treino.\n");
        sb.append("• CORRELAÇÃO POSITIVA (VO2MÁX x EFIC): Explique como o VO2máx estimado de hoje (")
                .append(String.format("%.1f", metrics.vo2MaxEstimado()))
                .append(") se correlaciona positivamente com o Efficiency Index (")
                .append(String.format("%.3f", metrics.efficiencyIndex()))
                .append("). Demonstre como a estabilidade do ritmo e o baixo custo cardiovascular por batimento hoje refletem ganhos reais em economia de corrida em relação à sua média histórica.\n");

        sb.append("• É ESTRITAMENTE PROIBIDO repetir explicações sobre PGC-1alpha, mitocôndrias ou autores já citados na Seção 2.0.\n\n");
        sb.append("4.0 - 🎯 CONCLUSÃO E PRÓXIMO PASSO \n");
        sb.append("- OBJETIVO DA SEÇÃO: Fazer um fechamento leve, sucinto e 100% focado no progresso prático para a Meia Maratona. É ESTRITAMENTE PROIBIDO repetir números, médias históricas ou teorias fisiológicas já explicadas nas seções 2.0 e 3.0.\n");
        sb.append("- LINGUAGEM: Use linguagem simples, descontraída e motivadora (como uma conversa direta de treinador para atleta).\n");
        sb.append("- REGRAS DINÂMICAS DE CONTEÚDO:\n");
        sb.append("  • Diga em poucas palavras o que o treino executado HOJE agrega na prática.\n");
        sb.append("  • Adapte a conexão com o próximo treino de acordo com o calendário real (ex: se hoje foi Terça, mencione o preparo para Quinta; se hoje foi Quinta, mencione o descanso/preparo para o Fim de Semana; se foi Fim de Semana, mencione a recuperação para a próxima Terça).\n");
        sb.append("  • Finalize com uma mensagem curta e empolgante focada na Meia Maratona de 31/10/2026.\n\n");

        sb.append("--- ESTRUTURA DO EXEMPLO DE CONCLUSÃO (ADAPTE AO DIA E TREINO REAL) ---\n");
        sb.append("✅ '4.0 - 🎯 CONCLUSÃO E PRÓXIMO PASSO\n");
        sb.append("  [Primeiro Nome], o treino de hoje [Tipo do Treino de Hoje] cumpriu perfeitamente o papel de [Benefício Prático em linguagem simples]. É essa consistência que garante o corpo pronto para [Conexão com o Próximo Treino Prescrito]!\n");
        sb.append("  Você tá construindo um ritmo de prova cada vez mais sólido. Mantém o foco e a disciplina que a Meia Maratona de 31/10 tá logo ali e você vai chegar voando! Tamo junto nessa! 🚀'\n\n");

        sb.append("5.0 - 🍽️ NUTRIÇÃO / DESCANSO 💤\n");
        sb.append("[Prescreva a estratégia detalhada de alimentação pré/pós treino e janelas de repouso conforme as diretrizes do MongoDB para o tipo de treino realizado HOJE. Se foi Z2 use 'light_moderated_days', se foi Tiros use 'high_intensity_days']\n\n");

        sb.append("6.0 - 📅 PRESCRIÇÃO STRAFIT PREDICT:\n\n");
        sb.append("PRÓXIMO TREINO: ").append(proximoTreinoData.toUpperCase()).append("\n\n");

        if (proximaPrescricao != null) {
            sb.append("TIPO DE ESTÍMULO: ").append(proximaPrescricao.getType()).append("\n\n");
            sb.append("NÍVEL ATUAL DE PROGRESSÃO: NÍVEL ").append(proximaPrescricao.getTargetLevel()).append("\n\n");
            sb.append("OBJETIVO: ").append(proximaPrescricao.getFocus()).append("\n\n");
            sb.append("ESTRUTURA DO TREINO:\n");
            sb.append("DURAÇÃO/VOLUME: ").append(proximaPrescricao.getDuration()).append("\n");
            sb.append("INTENSIDADE: ").append(proximaPrescricao.getIntensity()).append("\n");
            sb.append("MÉTODO: ").append(proximaPrescricao.getMethod()).append("\n\n");
        } else {
            sb.append("A prescrição para o próximo treino será atualizada pelo planejamento semanal na próxima segunda-feira.\n\n");
        }

        sb.append("--- INSTRUÇÃO TÉCNICA DO SISTEMA ---\n");
        sb.append("Ao final do relatório, adicione OBRIGATORIAMENTE o bloco XML abaixo UMA ÚNICA VEZ:\n");
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

    public record SessionMetrics(double fcMedia, double fcMax, int duracao, double stdDev, int zonaPredominante,
                                 double z2Percent, String comportamento, double fcMaxPercentage, double vo2MaxEstimado,
                                 double ganhoAlt, double efficiencyIndex, double safeDistance, String paceFormatted,
                                 Map<Integer, Double> zonePercentages) {
    }

    private SessionMetrics calcularMetricasSessao(List<StravaActivity.MinuteAnalysis> analysis, Double distance, Double averageSpeed, UserEntity user) {

        double fcMedia = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0.0);
        double fcMax = analysis.stream().map(StravaActivity.MinuteAnalysis::getMaxHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(0.0);
        int duracao = analysis.size();

        double variance = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(m -> Math.pow(m - fcMedia, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        int hrMax = user.getHrMax();
        int hrRest = user.getHrResting();

        List<Double> hrData = analysis.stream()
                .map(StravaActivity.MinuteAnalysis::getAverageHeartRate)
                .filter(Objects::nonNull)
                .toList();
        Map<Integer, Double> zonePercentages = activityService.calculateZonePercentages(hrData, hrMax, hrRest);

        int zonaPredominante = zonePercentages.entrySet().stream()
                .filter(entry -> entry.getKey() > 0)
                .max((entry1, entry2) -> {
                    int compare = entry1.getValue().compareTo(entry2.getValue());
                    if (compare == 0) {
                        return entry1.getKey().compareTo(entry2.getKey());
                    }
                    return compare;
                })
                .map(Map.Entry::getKey).orElse(0);

        double z2Percent = zonePercentages.getOrDefault(2, 0.0);

        double firstHalf = analysis.stream().limit(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        double secondHalf = analysis.stream().skip(duracao / 2).map(StravaActivity.MinuteAnalysis::getAverageHeartRate).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(fcMedia);
        String comportamento = (secondHalf > firstHalf * 1.05) ? "subindo gradualmente (drift)" : "predominantemente estável";

        double fcMaxPercentage = (fcMax / hrMax) * 100;
        double vo2MaxEstimado = 15.3 * (fcMax / (double) hrRest);

        DoubleSummaryStatistics elevStats = analysis.stream().map(StravaActivity.MinuteAnalysis::getAverageElevation).filter(Objects::nonNull).mapToDouble(Double::doubleValue).summaryStatistics();
        double ganhoAlt = elevStats.getMax() - elevStats.getMin();
        if (ganhoAlt < 0 || elevStats.getCount() == 0) ganhoAlt = 0.0;

        double safeDistance = distance != null ? distance : 0.0;
        // 🎯 Calcula o total de metros normalizados com compensação de altimetria (Minetti)
        double totalMetrosNormalizados = (analysis != null && !analysis.isEmpty())
                ? analysis.stream().mapToDouble(StravaActivity.MinuteAnalysis::getNormalizedSpeedMpm).sum()
                : 0.0;

// Se houver cálculo normalizado, usa ele; caso contrário, recorre à distância bruta como fallback
        double metrosParaCalculo = totalMetrosNormalizados > 0 ? totalMetrosNormalizados : (safeDistance * 1000.0);

        double efficiencyIndex = (fcMedia > 0 && duracao > 0) ? metrosParaCalculo / (fcMedia * duracao) : 0.0;
        String paceFormatted = formatSpeedToPace(averageSpeed);

        return new SessionMetrics(fcMedia, fcMax, duracao, stdDev, zonaPredominante, z2Percent, comportamento, fcMaxPercentage, vo2MaxEstimado, ganhoAlt, efficiencyIndex, safeDistance, paceFormatted, zonePercentages);
    }

    private String extractXmlBlock(String text) {
        Pattern pattern = Pattern.compile("<prescription_data>(.*?)</prescription_data>", Pattern.DOTALL);
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

    private String calcularProximaDataTreino(ZonedDateTime date, Set<DayOfWeek> diasDeTreino) {
        LocalDate today = LocalDate.now(ZONE_SP);
        LocalDate baseDate = date.toLocalDate().isBefore(today) ? today : date.toLocalDate();
        LocalDate dataIteracao = baseDate.plusDays(1);

        Set<DayOfWeek> diasValidos = (diasDeTreino != null && !diasDeTreino.isEmpty())
                ? diasDeTreino
                : Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);

        while (!diasValidos.contains(dataIteracao.getDayOfWeek())) {
            dataIteracao = dataIteracao.plusDays(1);
        }
        return dataIteracao.format(NEXT_WORKOUT_FORMATTER);
    }

    private int determinarCenarioAlvo(DayOfWeek diaAgendado, Set<DayOfWeek> diasDoAtleta) {
        if (diasDoAtleta == null || diasDoAtleta.isEmpty()) {
            return 1;
        }

        if (diasDoAtleta.size() == 1) {
            return 1;
        }

        List<DayOfWeek> diasOrdenados = diasDoAtleta.stream()
                .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .toList();

        return (diaAgendado == diasOrdenados.get(0)) ? 2 : 1;
    }

    private LocalDate parseNextWorkoutDate(String proximoTreinoData) {
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

    private double calcularMediaEficienciaDaLista(List<ActivitySummaryEntity> lista, int limiteItens) {
        if (lista == null || lista.isEmpty()) {
            return 0.0;
        }
        return lista.stream()
                .limit(limiteItens)
                .mapToDouble(ActivitySummaryEntity::getEfficiencyIndex)
                .average()
                .orElse(0.0);
    }

    private Set<DayOfWeek> parseTrainingDays(String trainingDaysStr) {
        if (trainingDaysStr == null || trainingDaysStr.isBlank()) {
            return Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);
        }
        try {
            return java.util.Arrays.stream(trainingDaysStr.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .map(DayOfWeek::valueOf)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[USER] Falha ao converter trainingDays ('{}'). Usando padrão Terça/Quinta/Sábado.", trainingDaysStr);
            return Set.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);
        }
    }
}