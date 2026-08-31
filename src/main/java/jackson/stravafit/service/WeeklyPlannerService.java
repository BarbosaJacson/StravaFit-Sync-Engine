package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivitySummaryRepository;
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
import java.time.ZoneId;
import java.util.List;
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


    private static final ZoneId ZONE_SP = ZoneId.of("America/Sao_Paulo");

    @Scheduled(cron = "0 0 16 * * MON", zone = "America/Sao_Paulo")
    @Transactional
    public void gerenciarPlanejamentoSemanal() {
        log.info("[WEEKLY PLANNER] Iniciando geração do plano de treino para a semana...");
        try {
            UserEntity user = userRepository.findById(1L)
                    .orElseThrow(() -> new IllegalStateException("Atleta principal não cadastrado."));

            List<ActivitySummaryEntity> treinosAnteriores = buscarTreinosSemanaAnterior();

            String respostaIA = gerarPrescricaoSemanalIA(user, treinosAnteriores);

            processarESalvarPrescricoes(respostaIA, user);
            log.info("[WEEKLY PLANNER] Planejamento semanal concluído com sucesso.");
        } catch (Exception e) {
            log.error("[WEEKLY PLANNER] Erro crítico ao gerar planejamento semanal: {}", e.getMessage(), e);
        }
    }

    public List<ActivitySummaryEntity> buscarTreinosSemanaAnterior() {
        LocalDate hoje = LocalDate.now(ZONE_SP);

        // Pega de Segunda-Feira da semana corrente até o final do dia de hoje (Sábado)
        LocalDateTime inicioSemana = hoje.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime fimSemana = hoje.atTime(23, 59, 59);

        log.info("[WEEKLY PLANNER] Buscando treinos da semana entre {} e {}", inicioSemana, fimSemana);

        return activitySummaryRepository.findByStartDateBetweenOrderByStartDateAsc(inicioSemana, fimSemana);
    }

    private int calcularNivelDinamicoCenario1(List<ActivitySummaryEntity> listaSabados, String proximoTreinoData) {
        boolean proximoTreinoEhSabado = proximoTreinoData.toUpperCase().contains("SÁBADO") || proximoTreinoData.toUpperCase().contains("SATURDAY");

        if (proximoTreinoEhSabado) {
            if (listaSabados.isEmpty()) {
                return 2;
            }

            ActivitySummaryEntity ultimoSabado = listaSabados.get(0);
            double ultimaDistanciaKm = ultimoSabado.getDistanceKm() != null ? ultimoSabado.getDistanceKm() : 0.0;

            double mediaSabados = listaSabados.stream()
                    .limit(5)
                    .mapToDouble(ActivitySummaryEntity::getEfficiencyIndex)
                    .average()
                    .orElse(0.0);

            if (ultimaDistanciaKm >= 11.5 && ultimaDistanciaKm < 13.5) {
                if (mediaSabados >= 1.08 && listaSabados.size() >= 4) {
                    return 3;
                }
                return 2;
            } else if (ultimaDistanciaKm >= 13.5 && ultimaDistanciaKm < 14.5) {
                if (mediaSabados >= 1.06 && listaSabados.size() >= 4) {
                    return 4;
                }
                return 3;
            } else if (ultimaDistanciaKm >= 14.5 && ultimaDistanciaKm < 15.5) {
                if (mediaSabados >= 1.04 && listaSabados.size() >= 4) {
                    return 5;
                }
                return 4;
            } else if (ultimaDistanciaKm >= 15.5) {
                return 5;
            }

            return 2;
        } else {
            return 1;
        }
    }

    private int calcularNivelDinamicoCenario2(List<ActivitySummaryEntity> historicoTiros, double mediaEficienciaTiros) {
        if (historicoTiros.isEmpty() || historicoTiros.size() < 5) {
            return 1;
        }

        if (mediaEficienciaTiros >= 1.15) {
            return 5;
        } else if (mediaEficienciaTiros >= 1.13) {
            return 4;
        } else if (mediaEficienciaTiros >= 1.10) {
            return 3;
        } else if (mediaEficienciaTiros >= 1.06) {
            return 2;
        }

        return 1;
    }

    public String gerarPrescricaoSemanalIA(UserEntity user, List<ActivitySummaryEntity> treinosAnteriores) {
        LocalDate hoje = LocalDate.now(ZONE_SP);

        LocalDate terca = hoje.with(DayOfWeek.TUESDAY);
        LocalDate quinta = hoje.with(DayOfWeek.THURSDAY);
        LocalDate sabado = hoje.with(DayOfWeek.SATURDAY);

        List<ActivitySummaryEntity> listaTiros = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(2);
        List<ActivitySummaryEntity> listaCenario1 = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

        List<ActivitySummaryEntity> listaSabados = listaCenario1.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .toList();

        double mediaEficienciaTiros = listaTiros.stream().limit(5).mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(0.0);

        int nivelTerca = calcularNivelDinamicoCenario1(listaSabados, "TERÇA-FEIRA");
        int nivelQuinta = calcularNivelDinamicoCenario2(listaTiros, mediaEficienciaTiros);
        int nivelSabado = calcularNivelDinamicoCenario1(listaSabados, "SÁBADO");

        // 🎯 BASE CIENTÍFICA NO MONGODB PASSANDO O ENUM GENDER
        String scientificContext = knowledgeService.getScientificContext(user.getGender());

        StringBuilder sb = new StringBuilder();
        sb.append("VOCÊ É O FISIOLOGISTA E TREINADOR CHEFE DO PROJETO STRAVAFIT.\n");
        sb.append("SUA MISSÃO É ANALISAR O HISTÓRICO DE ATIVIDADES REGISTRADAS NO MYSQL (TABELA activity_performance_summary), FAZER UM RESUMO DA SEMANA PASSADA, CONSULTAR AS DIRETRIZES DO MONGODB E GERAR O PLANEJAMENTO DA NOVA SEMANA PARA O ATLETA ")
                .append(user.getName().toUpperCase()).append(".\n\n");

        sb.append("REGRA MANDATÓRIA DE FORMATAÇÃO: GERE O CONTEÚDO DOS CAMPOS UTILIZANDO APENAS TEXTO PURO. É ESTRITAMENTE PROIBIDO O USO DE MARKDOWN (COMO ASTERISCOS, NEGRITO, HASHTAGS OU NEGRITOS DUPLOS) DENTRO DAS TAGS XML.\n\n");

        if (scientificContext != null && !scientificContext.isBlank()) {
            sb.append("--- BASE DE CONHECIMENTO CIENTÍFICO E DIRETRIZES DE PRESCRIÇÃO (MONGODB) ---\n");
            sb.append("INSTRUÇÃO DE CONSULTA: Consulte os documentos JSON abaixo e localize os parâmetros do Cenário e Nível de cada dia. Extraia exatamente o tipo de treino, a duração, a distância estimada, as faixas de FC/bpm, o foco técnico e o método detalhado.\n");
            sb.append(scientificContext).append("\n\n");
        }

        if (treinosAnteriores.isEmpty()) {
            sb.append("Nenhum treino registrado no MySQL na semana passada. Aplique os parâmetros padrão do MongoDB para retorno gradual.\n\n");
        } else {
            // 1. Título e instrução impressos apenas UMA vez antes da lista
            sb.append("--- HISTÓRICO DE PERFORMANCE E ANÁLISES DA SEMANA ANTERIOR (TABELA MYSQL: activity_performance_summary) ---\n")
                    .append("INSTRUÇÃO DE PROCESSAMENTO DA SEMANA: Consulte e analise os registros da tabela 'activity_performance_summary' listados abaixo. ")
                    .append("Sua missão é realizar a leitura interna dos dados e do campo 'ai_analysis_summary' de cada treino executado na semana anterior e gerar EXCLUSIVAMENTE uma síntese/conclusão geral do desempenho do atleta. ")
                    .append("Não reimprima os textos ou dados individuais de cada treino no relatório final; utilize-os apenas como insumo interno para compor a visão consolidada da semana na tag <weekly_overview>.\n\n");

            // 2. O laço percorre apenas os registros
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

        sb.append("--- DIRETRIZES DE CENÁRIO E NÍVEIS OBRIGATÓRIOS DO SISTEMA ---\n");
        sb.append("1. TERÇA-FEIRA (").append(terca).append("): Prescrever CENÁRIO 1 no NÍVEL ").append(nivelTerca).append(" (Rodagem Leve / Z2 - FATMAX).\n");
        sb.append("2. QUINTA-FEIRA (").append(quinta).append("): Prescrever CENÁRIO 2 no NÍVEL ").append(nivelQuinta).append(" (Intensificação / Tiros / VO2máx).\n");
        sb.append("3. SÁBADO (").append(sabado).append("): Prescrever CENÁRIO 1 no NÍVEL ").append(nivelSabado).append(" (Longão / Rodagem de Base - Zona 2).\n\n");

        sb.append("FORMATO DE SAÍDA EXIGIDO:\n");
        sb.append("Monte o planejamento baseado estritamente nas análises da semana lidas do MySQL cruzadas com as diretrizes do MongoDB. Retorne OBRIGATORIAMENTE o bloco XML a seguir:\n\n");
        sb.append("<weekly_prescription>\n");

        sb.append("  <weekly_overview>[Faça um breve balanço de 3 a 5 linhas analisando a consistência, eficiência e volume do atleta na semana anterior baseado nos treinos do MySQL.]</weekly_overview>\n\n");

        sb.append("  <prescription>\n");
        sb.append("    <scheduled_date>").append(terca).append("</scheduled_date>\n");
        sb.append("    <type>[Tipo do treino conforme MongoDB]</type>\n");
        sb.append("    <distance_km>[Distância conforme MongoDB ex: 10.0]</distance_km>\n");
        sb.append("    <duration>[Duração em minutos conforme MongoDB]</duration>\n");
        sb.append("    <intensity>[Faixas de FC e Zonas conforme MongoDB]</intensity>\n");
        sb.append("    <focus>[Foco técnico do nível no MongoDB]</focus>\n");
        sb.append("    <method>[Descrição detalhada do método conforme MongoDB]</method>\n");
        sb.append("    <justification>[Justificativa fisiológica da escolha]</justification>\n");
        sb.append("    <target_scenario>1</target_scenario>\n");
        sb.append("    <target_level>").append(nivelTerca).append("</target_level>\n");
        sb.append("  </prescription>\n");

        sb.append("  <prescription>\n");
        sb.append("    <scheduled_date>").append(quinta).append("</scheduled_date>\n");
        sb.append("    <type>[Tipo do treino conforme MongoDB]</type>\n");
        sb.append("    <distance_km>[Distância conforme MongoDB ex: 7.8]</distance_km>\n");
        sb.append("    <duration>[Duração em minutos conforme MongoDB]</duration>\n");
        sb.append("    <intensity>[Faixas de FC e Zonas conforme MongoDB]</intensity>\n");
        sb.append("    <focus>[Foco técnico do nível no MongoDB]</focus>\n");
        sb.append("    <method>[Detalhamento de Séries, Tiros, Pausas e Zonas conforme MongoDB]</method>\n");
        sb.append("    <justification>[Justificativa fisiológica da escolha dos tiros]</justification>\n");
        sb.append("    <target_scenario>2</target_scenario>\n");
        sb.append("    <target_level>").append(nivelQuinta).append("</target_level>\n");
        sb.append("  </prescription>\n");

        sb.append("  <prescription>\n");
        sb.append("    <scheduled_date>").append(sabado).append("</scheduled_date>\n");
        sb.append("    <type>[Tipo do treino conforme MongoDB]</type>\n");
        sb.append("    <distance_km>[Distância conforme MongoDB ex: 16.0]</distance_km>\n");
        sb.append("    <duration>[Duração em minutos conforme MongoDB]</duration>\n");
        sb.append("    <intensity>[Faixas de FC e Zonas conforme MongoDB]</intensity>\n");
        sb.append("    <focus>[Foco técnico do nível no MongoDB]</focus>\n");
        sb.append("    <method>[Descrição detalhada do método do Longão conforme MongoDB]</method>\n");
        sb.append("    <justification>[Justificativa fisiológica do volume]</justification>\n");
        sb.append("    <target_scenario>1</target_scenario>\n");
        sb.append("    <target_level>").append(nivelSabado).append("</target_level>\n");
        sb.append("  </prescription>\n");

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

        String overview = sanitizeText(extractTagValue(rawXml, "weekly_overview"));
        if (overview != null && !overview.isBlank()) {
            msgTelegram.append("📊 RESUMO DA SEMANA ANTERIOR:\n")
                    .append(overview).append("\n\n")
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
                    .findByScheduledDate(scheduledDate)
                    .orElse(new WorkoutPrescriptionEntity());

            prescription.setScheduledDate(scheduledDate);
            prescription.setActivityId(0L);
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

            String method = sanitizeText(extractTagValue(block, "method"));
            String justification = sanitizeText(extractTagValue(block, "justification"));

            if (justification != null && !justification.isBlank()) {
                prescription.setMethod(method + "\n\n💡 Justificativa Técnica: " + justification);
            } else {
                prescription.setMethod(method);
            }

            String targetScenarioStr = extractTagValue(block, "target_scenario");
            String targetLevelStr = extractTagValue(block, "target_level");

            int scenario = targetScenarioStr != null ? Integer.parseInt(targetScenarioStr.trim()) : 1;
            int level = targetLevelStr != null ? Integer.parseInt(targetLevelStr.trim()) : 1;

            prescription.setTargetScenario(scenario);
            prescription.setTargetLevel(level);
            prescription.setRawGeminiResponse(block);

            workoutPrescriptionRepository.save(prescription);
            log.info("[WEEKLY PLANNER] Prescrição salva no MySQL para a data: {}", scheduledDate);

            // Monta o resumo formatado em Texto Puro para o Telegram
            msgTelegram.append("📌 ").append(scheduledDate.getDayOfWeek().toString()).append(" (").append(scheduledDate).append(")\n")
                    .append("• Treino: ").append(prescription.getType()).append(" | Nível ").append(level).append("\n")
                    .append("• Volume / Distância: ").append(distanceStr != null ? distanceStr + " km" : "N/A").append(" (").append(prescription.getDuration()).append(")\n")
                    .append("• Intensidade: ").append(prescription.getIntensity()).append("\n")
                    .append("• Estrutura / Método: ").append(method).append("\n")
                    .append("• Foco Técnico: ").append(prescription.getFocus()).append("\n\n");
        }

        telegramClient.sendMessage(sanitizeText(msgTelegram.toString()));
        log.info("[WEEKLY PLANNER] Notificação enviada para o Telegram.");
    }

    private String extractTagValue(String xml, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">");
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
}