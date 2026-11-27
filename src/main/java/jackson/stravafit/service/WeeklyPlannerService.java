package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.model.UserGoalEntity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivitySummaryRepository;
import jackson.stravafit.repository.UserGoalRepository;
import jackson.stravafit.repository.UserRepository;
import jackson.stravafit.repository.WorkoutPrescriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyPlannerService {

    private final GeminiClient geminiClient;
    private final ActivitySummaryRepository activitySummaryRepository;
    private final WorkoutPrescriptionRepository workoutPrescriptionRepository;
    private final UserRepository userRepository;
    private final KnowledgeService knowledgeService;
    private final TelegramClient telegramClient;
    private final UserGoalRepository userGoalRepository;
    private final UserGoalService userGoalService;

    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");

    @Scheduled(cron = "0 0 16 * * MON", zone = "America/Sao_Paulo")
    @Transactional
    public void gerenciarPlanejamentoSemanal() {
        log.info("[WEEKLY PLANNER] Iniciando geração do plano de treino semanal para os atletas...");
        List<UserEntity> usuarios = userRepository.findAll();

        if (usuarios.isEmpty()) {
            log.warn("[WEEKLY PLANNER] Nenhum usuário cadastrado no MySQL.");
            return;
        }

        for (UserEntity user : usuarios) {
            gerenciarPlanejamentoSemanalParaUsuario(user);
        }
    }

    @Transactional
    public void gerenciarPlanejamentoSemanalParaUsuario(UserEntity user) {
        log.info("[WEEKLY PLANNER] Gerando planejamento semanal para o atleta: {} (ID: {})", user.getName(), user.getId());
        try {
            List<ActivitySummaryEntity> treinosAnteriores = buscarTreinosSemanaAnterior(user.getId());

            String respostaIA = gerarPrescricaoSemanalIA(user, treinosAnteriores);

            processarESalvarPrescricoes(respostaIA, user);
            log.info("[WEEKLY PLANNER] Planejamento semanal concluído para {}.", user.getName());
        } catch (Exception e) {
            log.error("[WEEKLY PLANNER] Erro crítico ao gerar planejamento semanal para {}: {}", user.getName(), e.getMessage(), e);
        }
    }

    public List<ActivitySummaryEntity> buscarTreinosSemanaAnterior(Long userId) {
        LocalDate hoje = LocalDate.now(ZONE_SP);

        LocalDateTime inicioSemana = hoje.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime fimSemana = hoje.atTime(23, 59, 59);

        log.info("[WEEKLY PLANNER] Buscando treinos da semana entre {} e {} para userId={}", inicioSemana, fimSemana, userId);

        return activitySummaryRepository.findByUserIdAndStartDateBetweenOrderByStartDateAsc(userId, inicioSemana, fimSemana);
    }

    private int determinarNivelPorEI(double mediaEI, String gender, String goalCategory, int cenario) {
        double[] cortes = obterCortesNivel(gender, goalCategory, cenario);
        for (int i = cortes.length - 1; i >= 0; i--) {
            if (mediaEI >= cortes[i]) {
                return i + 2; // cortes[0] -> nível 2, ..., cortes[10] -> nível 12
            }
        }
        return 1;
    }

    private double[] obterCortesNivel(String gender, String goalCategory, int cenario) {
        boolean isFemale = "FEMALE".equalsIgnoreCase(gender);
        String goal = goalCategory != null ? goalCategory.trim().toUpperCase() : "ROUTINE_CONSISTENCY";

        // Cortes mínimos de mediaEI para atingir os níveis 2 até 12 (nível 1 é o padrão abaixo de cortes[0])
        if (isFemale && "ROUTINE_CONSISTENCY".equals(goal) && cenario == 1) {
            return new double[]{0.98, 1.00, 1.02, 1.03, 1.04, 1.06, 1.07, 1.09, 1.10, 1.11, 1.13};
        }
        if (isFemale && "CARDIO_LONGEVITY".equals(goal) && cenario == 1) {
            return new double[]{1.01, 1.03, 1.05, 1.07, 1.08, 1.10, 1.12, 1.13, 1.15, 1.17, 1.19};
        }
        if (isFemale && "HIGH_PERFORMANCE".equals(goal) && cenario == 1) {
            return new double[]{1.00, 1.02, 1.04, 1.06, 1.08, 1.10, 1.12, 1.14, 1.16, 1.17, 1.19};
        }
        if (isFemale && cenario == 2) {
            return new double[]{1.00, 1.02, 1.04, 1.06, 1.08, 1.10, 1.12, 1.14, 1.16, 1.17, 1.19};
        }
        // Padrão masculino / fallback alinhado à escala 1.00 -> 1.20
        return new double[]{1.00, 1.02, 1.04, 1.06, 1.08, 1.10, 1.12, 1.14, 1.16, 1.18, 1.20};
    }

    private double calcularMediaEIDosUltimos5Dias(
            List<ActivitySummaryEntity> historicoCenario,
            DayOfWeek diaDaSemana) {
        if (historicoCenario == null || historicoCenario.isEmpty() || diaDaSemana == null) {
            return 1.00;
        }
        List<ActivitySummaryEntity> ultimosDoDia = historicoCenario.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == diaDaSemana)
                .limit(5)
                .toList();

        return ultimosDoDia.stream()
                .filter(a -> a.getEfficiencyIndex() != null)
                .mapToDouble(ActivitySummaryEntity::getEfficiencyIndex)
                .average()
                .orElse(1.00);
    }

    public String gerarPrescricaoSemanalIA(UserEntity user, List<ActivitySummaryEntity> treinosAnteriores) {
        LocalDate hoje = LocalDate.now(ZONE_SP);
        List<DayOfWeek> diasConfigurados = resolverDiasDeTreino(user.getTrainingDays());
        Integer frequenciaSemanal = user.getWeeklyFrequency();
        if (frequenciaSemanal != null && frequenciaSemanal > 0 && diasConfigurados.size() > frequenciaSemanal) {
            diasConfigurados = diasConfigurados.subList(0, frequenciaSemanal);
        }
        List<DayOfWeek> diasSelecionados = diasConfigurados;

        List<ActivitySummaryEntity> listaTiros = activitySummaryRepository
                .findTop10ByUserIdAndDetectedScenarioOrderByStartDateDesc(user.getId(), 2);
        List<ActivitySummaryEntity> listaCenario1 = activitySummaryRepository
                .findTop10ByUserIdAndDetectedScenarioOrderByStartDateDesc(user.getId(), 1);

        String gender = user.getGender() != null ? user.getGender().name() : "MALE";
        String goalCategory = userGoalRepository
                .findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId())
                .map(g -> g.getCategory() != null ? g.getCategory().name() : "ROUTINE_CONSISTENCY")
                .orElse("ROUTINE_CONSISTENCY");

        Map<DayOfWeek, Double> mediasEIPorDia = new LinkedHashMap<>();
        Map<DayOfWeek, Integer> niveisPorDia = new LinkedHashMap<>();

        for (int i = 0; i < diasConfigurados.size(); i++) {
            DayOfWeek dia = diasConfigurados.get(i);
            int cenarioDia = (i % 2 == 1) ? 2 : 1;
            List<ActivitySummaryEntity> baseHistorico = (cenarioDia == 2) ? listaTiros : listaCenario1;
            double mediaDia = calcularMediaEIDosUltimos5Dias(baseHistorico, dia);
            int nivelDia = determinarNivelPorEI(mediaDia, gender, goalCategory, cenarioDia);
            mediasEIPorDia.put(dia, mediaDia);
            niveisPorDia.put(dia, nivelDia);
        }

        String scientificContext = knowledgeService.getScientificContext(user.getGender());
        String fonteZonas = "EXAM".equalsIgnoreCase(user.getHrZoneSource())
                ? "EXAME DE ERGOSPIROMETRIA (LV1/LV2)"
                : "FÓRMULA DE FC (Karvonen / FC máx e repouso)";

        Optional<UserGoalEntity> objetivoAtivoOpt = userGoalRepository
                .findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(user.getId());
        UserGoalEntity objetivoAtivo = objetivoAtivoOpt.orElse(null);
        String styleContextMongo = userGoalService.getActiveStyleContext(user);

        Integer idade = null;
        if (user.getBirthDate() != null) {
            idade = Period.between(user.getBirthDate(), hoje).getYears();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("VOCÊ É O FISIOLOGISTA E TREINADOR CHEFE DO PROJETO STRAVAFIT.\n");
        sb.append("SUA MISSÃO É ANALISAR O HISTÓRICO DE ATIVIDADES REGISTRADAS NO MYSQL (TABELA activity_performance_summary), FAZER UM RESUMO DA SEMANA PASSADA, CONSULTAR AS DIRETRIZES DO MONGODB E GERAR O PLANEJAMENTO DA NOVA SEMANA PARA O ATLETA ")
                .append(user.getName().toUpperCase()).append(".\n\n");

        sb.append("REGRA MANDATÓRIA DE FORMATAÇÃO: GERE O CONTEÚDO DOS CAMPOS UTILIZANDO APENAS TEXTO PURO. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN (COMO ASTERISCOS, NEGRITO, HASHTAGS OU NEGRITOS DUPLOS) DENTRO DAS TAGS XML.\n\n");

        sb.append("TOM DE VOZ E EMPATIA:\n");
        sb.append("Seja extremamente amigável, próximo e encorajador. Fale diretamente com o atleta chamando-o pelo primeiro nome (").append(user.getName()).append("). Mantenha uma postura de parceiro técnico que vibra com o progresso do atleta.\n\n");

        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO CIENTÍFICO E DIRETRIZES DE PRESCRIÇÃO (MONGODB) ---\n");
            sb.append("INSTRUÇÃO DE CONSULTA: Consulte os documentos JSON abaixo e localize os parâmetros do Cenário e Nível de cada dia. Extraia exatamente o tipo de treino, a duração, a distância estimada, as faixas de FC/bpm, o foco técnico e o método detalhado.\n");
            sb.append(scientificContext).append("\n\n");
        }

        if (treinosAnteriores.isEmpty()) {
            sb.append("Nenhum treino registrado no MySQL na semana passada. Aplique os parâmetros padrão do MongoDB para retorno gradual.\n\n");
        } else {
            sb.append("--- HISTÓRICO DE PERFORMANCE E ANÁLISES DA SEMANA ANTERIOR (TABELA MYSQL: activity_performance_summary) ---\n")
                    .append("INSTRUÇÃO DE PROCESSAMENTO DA SEMANA: Consulte e analise os registros da tabela 'activity_performance_summary' listados abaixo. ")
                    .append("Sua missão é realizar a leitura interna dos dados e do campo 'ai_analysis_summary' de cada treino executado na semana anterior e gerar EXCLUSIVAMENTE uma síntese/conclusão geral do desempenho do atleta. ")
                    .append("Não reimprima os textos ou dados individuais de cada treino no relatório final; utilize-os apenas como insumo interno para compor a visão consolidada da semana na tag <weekly_overview>.\n\n");

            for (ActivitySummaryEntity act : treinosAnteriores) {
                LocalDate dataTreino = act.getStartDate() != null ? act.getStartDate().toLocalDate() : hoje;

                sb.append(String.format("• Data: %s | Distância: %.2f km | Duração: %d min | FC Méd: %.0f bpm | Eficiência: %.3f | Cenário: %d\n",
                        dataTreino,
                        act.getDistanceKm() != null ? act.getDistanceKm() : 0.0,
                        act.getTotalTimeMinutes() != null ? act.getTotalTimeMinutes() : 0,
                        act.getAverageHeartRate() != null ? act.getAverageHeartRate() : 0.0,
                        act.getEfficiencyIndex() != null ? act.getEfficiencyIndex() : 0.0,
                        act.getDetectedScenario() != null ? act.getDetectedScenario() : 1));

                if (act.getAiAnalysisSummary() != null && !act.getAiAnalysisSummary().isBlank()) {
                    sb.append("   Análise Realizada da Atividade: ")
                            .append(act.getAiAnalysisSummary().replaceAll("\n", " "))
                            .append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("--- PERFIL FISIOLÓGICO E ZONAS DO ATLETA ---\n");
        sb.append("- Gênero: ").append(gender).append("\n");
        sb.append("- Origem das Zonas Cardíacas: ").append(fonteZonas).append("\n");
        if (idade != null) {
            sb.append("- Idade: ").append(idade).append(" anos\n");
        }
        if (user.getHrMax() != null) {
            sb.append("- FC Máxima: ").append(user.getHrMax()).append(" bpm\n");
        }
        if (user.getHrResting() != null) {
            sb.append("- FC de Repouso: ").append(user.getHrResting()).append(" bpm\n");
        }
        if ("EXAM".equalsIgnoreCase(user.getHrZoneSource()) && user.getZ2MaxBpm() != null && user.getZ4MaxBpm() != null) {
            sb.append("- Limiares de Referência (Z2 Máx / LV1 e Z4 Máx / LV2): ")
                    .append(user.getZ2MaxBpm()).append(" bpm / ").append(user.getZ4MaxBpm()).append(" bpm\n");
        }
        sb.append("\n");

        sb.append("--- OBJETIVO ATIVO E DIRETRIZ DE ESTILO DO ATLETA ---\n");
        if (objetivoAtivo != null) {
            sb.append("- Categoria do Objetivo: ").append(objetivoAtivo.getCategory());
            if (objetivoAtivo.getCategory() != null) {
                sb.append(" (").append(objetivoAtivo.getCategory().getLabel()).append(")");
            }
            sb.append("\n");
            sb.append("- Código de Estilo de Treino: ").append(objetivoAtivo.getTrainingStyleCode()).append("\n");
            if (objetivoAtivo.getEvent() != null) {
                sb.append("- Evento/Prova Vinculada: ").append(objetivoAtivo.getEvent().getEventName())
                        .append(" | Data: ").append(objetivoAtivo.getEvent().getEventDate())
                        .append(" | Distância Alvo: ").append(objetivoAtivo.getEvent().getTargetDistanceKm()).append(" km\n");
            }
        } else {
            sb.append("- Objetivo Ativo: Não definido (usar rotina/consistência padrão).\n");
        }
        sb.append("- Frequência Semanal Configurada: ").append(user.getWeeklyFrequency()).append(" dias\n");
        sb.append("- Dias de Treino Configurados: ").append(user.getTrainingDays()).append("\n");
        if (styleContextMongo != null && !styleContextMongo.isBlank()) {
            sb.append("- Diretriz do Estilo no MongoDB:\n").append(styleContextMongo).append("\n");
        }
        sb.append("\n");

        sb.append("--- DIRETRIZES DE CENÁRIO E NÍVEIS OBRIGATÓRIOS DO SISTEMA ---\n");
        for (int i = 0; i < diasConfigurados.size(); i++) {
            DayOfWeek dia = diasConfigurados.get(i);
            LocalDate dataDia = hoje.with(java.time.temporal.TemporalAdjusters.nextOrSame(dia));
            int cenarioDia = (i % 2 == 1) ? 2 : 1;
            int nivelDia = niveisPorDia.getOrDefault(dia, 1);
            double mediaDia = mediasEIPorDia.getOrDefault(dia, 1.00);
            String descCenario = (cenarioDia == 2)
                    ? "CENÁRIO 2 (Intensificação / Tiros / VO2máx)"
                    : "CENÁRIO 1 (Rodagem Leve / Base Aeróbica / Z2)";
            sb.append((i + 1)).append(". ").append(dia).append(" (").append(dataDia)
                    .append("): Prescrever ").append(descCenario)
                    .append(" no NÍVEL ").append(nivelDia)
                    .append(String.format(" (Média EI últimos 5 treinos do dia: %.3f).\n", mediaDia));
        }
        sb.append("\n");

        sb.append("--- DIAS DE TREINO SELECIONADOS PELO ATLETA ---\n");
        for (DayOfWeek dia : diasSelecionados) {
            LocalDate dataDia = hoje.with(dia);
            sb.append("- ").append(dia).append(" (").append(dataDia).append(")\n");
        }
        sb.append("INSTRUÇÃO DE DIAS: Gere um bloco <prescription> com <scheduled_date> correspondente a cada dia listado acima (respeitando a quantidade e os dias escolhidos pelo atleta, com o nível adequado ao estímulo de cada sessão).\n\n");

        if (user.getWeeklyFrequency() != null) {
            sb.append("LIMITE DE FREQUÊNCIA DO ATLETA: O atleta informou disponibilidade máxima de ")
                    .append(user.getWeeklyFrequency())
                    .append(" treino(s) nesta semana. Não prescreva mais do que ")
                    .append(user.getWeeklyFrequency())
                    .append(" sessão(ões) na semana e ajuste a distribuição/carga para caber nesse limite com segurança.\n\n");
        }

        sb.append("FORMATO DE SAÍDA EXIGIDO:\n");
        sb.append("Monte o planejamento baseado estritamente nas análises da semana lidas do MySQL cruzadas com as diretrizes do MongoDB. Retorne OBRIGATORIAMENTE o bloco XML a seguir:\n\n");
        sb.append("<weekly_prescription>\n");

        sb.append("  <weekly_overview>[Faça um breve balanço de 3 a 5 linhas analisando a consistência, eficiência e volume do atleta na semana anterior baseado nos treinos do MySQL.]</weekly_overview>\n\n");

        for (int i = 0; i < diasConfigurados.size(); i++) {
            DayOfWeek dia = diasConfigurados.get(i);
            LocalDate dataDia = hoje.with(dia);
            int cenarioDia = (i % 2 == 1) ? 2 : 1;
            int nivelDia = niveisPorDia.getOrDefault(dia, 1);

            sb.append("  <prescription>\n");
            sb.append("    <scheduled_date>").append(dataDia).append("</scheduled_date>\n");
            sb.append("    <type>[Tipo do treino conforme MongoDB]</type>\n");
            sb.append("    <distance_km>[Distância conforme MongoDB ex: 10.0]</distance_km>\n");
            sb.append("    <duration>[Duração em minutos conforme MongoDB]</duration>\n");
            sb.append("    <intensity>[Faixas de FC e Zonas conforme MongoDB]</intensity>\n");
            sb.append("    <focus>[Foco técnico do nível no MongoDB]</focus>\n");
            sb.append("    <method>[Descrição detalhada do método conforme MongoDB]</method>\n");
            sb.append("    <justification>[Justificativa fisiológica da escolha]</justification>\n");
            sb.append("    <target_scenario>").append(cenarioDia).append("</target_scenario>\n");
            sb.append("    <target_level>").append(nivelDia).append("</target_level>\n");
            sb.append("  </prescription>\n\n");
        }

        sb.append("  <weekly_conclusion>")
                .append("Escreva um texto motivador de 3 a 5 linhas parabenizando o atleta ")
                .append(user.getName())
                .append(" pela dedicação contínua e incentivando a consistência na nova semana de treinos.")
                .append("</weekly_conclusion>\n");

        sb.append("</weekly_prescription>\n");

        return geminiClient.getInsight(sb.toString());
    }

    public void processarESalvarPrescricoes(String rawXml, UserEntity user) {
        if (rawXml == null || !rawXml.contains("<weekly_prescription>")) {
            log.error("[WEEKLY PLANNER] Resposta inválida ou sem bloco <weekly_prescription> da IA.");
            return;
        }

        StringBuilder msgTelegram = new StringBuilder();
        msgTelegram.append("📅 PLANEJAMENTO SEMANAL STRAFIT\n");
        msgTelegram.append("Atleta: ").append(user.getName()).append("\n\n");

        String overview = extractTagValue(rawXml, "weekly_overview");
        if (overview != null && !overview.isBlank()) {
            msgTelegram.append("📊 RESUMO DA SEMANA ANTERIOR:\n")
                    .append(sanitizeText(overview)).append("\n\n")
                    .append("------------------------------------\n\n");
        }

        Pattern prescriptionPattern = Pattern.compile("<prescription>(.*?)</prescription>", Pattern.DOTALL);
        Matcher matcher = prescriptionPattern.matcher(rawXml);

        while (matcher.find()) {
            String block = matcher.group(1);

            String scheduledDateStr = extractTagValue(block, "scheduled_date");
            if (scheduledDateStr == null) continue;

            LocalDate scheduledDate = LocalDate.parse(scheduledDateStr.replaceAll("[^0-9-]", ""));

            WorkoutPrescriptionEntity prescription = workoutPrescriptionRepository
                    .findByUserIdAndScheduledDate(user.getId(), scheduledDate)
                    .orElse(new WorkoutPrescriptionEntity());

            prescription.setUser(user);
            prescription.setScheduledDate(scheduledDate);
            prescription.setType(sanitizeText(extractTagValue(block, "type")));
            prescription.setDuration(sanitizeText(extractTagValue(block, "duration")));
            prescription.setIntensity(sanitizeText(extractTagValue(block, "intensity")));
            prescription.setFocus(sanitizeText(extractTagValue(block, "focus")));

            String distanceStr = extractTagValue(block, "distance_km");
            if (distanceStr != null && !distanceStr.isBlank()) {
                try {
                    prescription.setDistanceKm(Double.parseDouble(distanceStr.replaceAll("[^0-9.]", "")));
                } catch (Exception e) {
                    prescription.setDistanceKm(0.0);
                }
            }

            String rawMethod = extractTagValue(block, "method");
            String justification = extractTagValue(block, "justification");

            String methodCompleto = "";
            if (rawMethod != null && !rawMethod.isBlank()) {
                methodCompleto = rawMethod.replaceAll("[*#`]", "").trim();
                methodCompleto = methodCompleto.replaceAll("(?i)(FASE \\d+ —)", "\n\n$1").trim();
            }

            if (justification != null && !justification.isBlank()) {
                methodCompleto += "\n\n💡 Justificativa Técnica: " + sanitizeText(justification);
            }

            prescription.setMethod(methodCompleto);

            String targetScenarioStr = extractTagValue(block, "target_scenario");
            String targetLevelStr = extractTagValue(block, "target_level");

            int scenario = targetScenarioStr != null ? Integer.parseInt(targetScenarioStr.trim()) : 1;
            int level = targetLevelStr != null ? Integer.parseInt(targetLevelStr.trim()) : 1;

            prescription.setTargetScenario(scenario);
            prescription.setTargetLevel(level);
            prescription.setRawGeminiResponse(block);

            workoutPrescriptionRepository.save(prescription);
            log.info("[WEEKLY PLANNER] Prescrição salva no MySQL para a data: {}", scheduledDate);

            msgTelegram.append("📌 ").append(scheduledDate.getDayOfWeek().toString()).append(" (").append(scheduledDate).append(")\n")
                    .append("• Treino: ").append(prescription.getType()).append(" | Nível ").append(level).append("\n")
                    .append("• Volume / Distância: ").append(distanceStr != null ? distanceStr + " km" : "N/A").append(" (").append(prescription.getDuration()).append(")\n")
                    .append("• Intensidade: ").append(prescription.getIntensity()).append("\n")
                    .append("• Foco Técnico: ").append(prescription.getFocus()).append("\n\n")
                    .append("• Estrutura / Método:\n").append(methodCompleto).append("\n\n")
                    .append("------------------------------------\n\n");
        }

        String conclusion = extractTagValue(rawXml, "weekly_conclusion");
        if (conclusion != null && !conclusion.isBlank()) {
            msgTelegram.append("🚀 CONCLUSÃO E MOTIVAÇÃO:\n")
                    .append(sanitizeText(conclusion)).append("\n\n")
                    .append("------------------------------------\n\n");
        }

        telegramClient.sendMessage(msgTelegram.toString());
        log.info("[WEEKLY PLANNER] Notificação enviada para o Telegram.");
    }

    private String extractTagValue(String xml, String tag) {
        if (xml == null || tag == null) return null;
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String sanitizeText(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#`]", "").trim();
    }

    private List<DayOfWeek> resolverDiasDeTreino(String trainingDaysStr) {
        if (trainingDaysStr == null || trainingDaysStr.isBlank()) {
            return List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY);
        }
        List<DayOfWeek> dias = Arrays.stream(trainingDaysStr.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(dia -> switch (dia) {
                    case "SEGUNDA", "SEGUNDA-FEIRA", "MONDAY" -> DayOfWeek.MONDAY;
                    case "TERÇA", "TERCA", "TERÇA-FEIRA", "TERCA-FEIRA", "TUESDAY" -> DayOfWeek.TUESDAY;
                    case "QUARTA", "QUARTA-FEIRA", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
                    case "QUINTA", "QUINTA-FEIRA", "THURSDAY" -> DayOfWeek.THURSDAY;
                    case "SEXTA", "SEXTA-FEIRA", "FRIDAY" -> DayOfWeek.FRIDAY;
                    case "SÁBADO", "SABADO", "SATURDAY" -> DayOfWeek.SATURDAY;
                    case "DOMINGO", "SUNDAY" -> DayOfWeek.SUNDAY;
                    default -> DayOfWeek.valueOf(dia);
                })
                .distinct()
                .sorted(Comparator.comparingInt(DayOfWeek::getValue))
                .toList();
        return dias.isEmpty()
                ? List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
                : dias;
    }
}