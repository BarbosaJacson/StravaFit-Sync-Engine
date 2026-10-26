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

        LocalDateTime inicioSemana = hoje.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime fimSemana = hoje.atTime(23, 59, 59);

        log.info("[WEEKLY PLANNER] Buscando treinos da semana entre {} e {}", inicioSemana, fimSemana);

        return activitySummaryRepository.findByStartDateBetweenOrderByStartDateAsc(inicioSemana, fimSemana);
    }

    private int determinarNivelPorEI(double mediaEI) {
        if (mediaEI >= 1.15) return 5;
        if (mediaEI >= 1.10) return 4;
        if (mediaEI >= 1.05) return 3;
        if (mediaEI >= 1.00) return 2;
        return 1;
    }

    public String gerarPrescricaoSemanalIA(UserEntity user, List<ActivitySummaryEntity> treinosAnteriores) {
        LocalDate hoje = LocalDate.now(ZONE_SP);

        LocalDate terca = hoje.with(DayOfWeek.TUESDAY);
        LocalDate quinta = hoje.with(DayOfWeek.THURSDAY);
        LocalDate sabado = hoje.with(DayOfWeek.SATURDAY);

        boolean ehSemanaDaProva = sabado.equals(LocalDate.of(2026, 10, 31));

        List<ActivitySummaryEntity> listaTiros = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(2);
        List<ActivitySummaryEntity> listaCenario1 = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

        List<ActivitySummaryEntity> ultimasTercas = listaCenario1.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.TUESDAY)
                .limit(5).toList();

        List<ActivitySummaryEntity> ultimasQuintas = listaTiros.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.THURSDAY)
                .limit(5).toList();

        List<ActivitySummaryEntity> ultimosSabados = listaCenario1.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .limit(5).toList();

        double mediaEI_Terca = ultimasTercas.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);
        double mediaEI_Quinta = ultimasQuintas.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);
        double mediaEI_Sabado = ultimosSabados.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);

        int nivelTerca  = determinarNivelPorEI(mediaEI_Terca);
        int nivelQuinta = determinarNivelPorEI(mediaEI_Quinta);
        int nivelSabado = determinarNivelPorEI(mediaEI_Sabado);

        String scientificContext = knowledgeService.getScientificContext(user.getGender());

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

        sb.append("--- DIRETRIZES DE CENÁRIO E NÍVEIS OBRIGATÓRIOS DO SISTEMA ---\n");
        sb.append("1. TERÇA-FEIRA (").append(terca).append("): Prescrever CENÁRIO 1 no NÍVEL ").append(nivelTerca).append(" (Rodagem Leve / Z2 - FATMAX).\n");
        sb.append("2. QUINTA-FEIRA (").append(quinta).append("): Prescrever CENÁRIO 2 no NÍVEL ").append(nivelQuinta).append(" (Intensificação / Tiros / VO2máx).\n");

        if (ehSemanaDaProva) {
            sb.append("3. SÁBADO (").append(sabado).append("): GATILHO DE PROVA ATIVADO (31/10/2026). MEIA MARATONA OFICIAL (21.1 km).\n\n");
        } else {
            sb.append("3. SÁBADO (").append(sabado).append("): Prescrever CENÁRIO 1 no NÍVEL ").append(nivelSabado).append(" (Longão / Rodagem de Base - Zona 2).\n\n");
        }

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
        sb.append("    <method>[Faça o detalhamento de Séries de forma amigável com o atleta sem muita formalidade,especifique os Tiros, Pausas e Zonas conforme MongoDB]</method>\n");
        sb.append("    <justification>[Justificativa fisiológica da escolha dos tiros]</justification>\n");
        sb.append("    <target_scenario>2</target_scenario>\n");
        sb.append("    <target_level>").append(nivelQuinta).append("</target_level>\n");
        sb.append("  </prescription>\n");

        if (ehSemanaDaProva) {
            sb.append("  <prescription>\n");
            sb.append("    <scheduled_date>").append(sabado).append("</scheduled_date>\n");
            sb.append("    <type>PROVA OFICIAL - MEIA MARATONA (21.1 KM)</type>\n");
            sb.append("    <distance_km>21.1</distance_km>\n");
            sb.append("    <duration>130 a 135 minutos (2h10min a 2h15min)</duration>\n");
            sb.append("    <intensity>Gestão Z2 no início (125-139 bpm) progredindo para Z3/Z4 sustentado nos km finais</intensity>\n");
            sb.append("    <focus>Execução do plano de prova de 21.1 km. Controle rigoroso de pacing, hidratação constante, gestão de altimetria nas pontes/viadutos e reserva funcional para os km 15 a 21.</focus>\n");
            sb.append("    <method>FASE 1 — KM 0 AO KM 3\n");
            sb.append("OBJETIVO DO TRECHO\nAclimação, adaptação às condições ambientais, controle de ansiedade e acomodação no pelotão em Z2 pura.\n");
            sb.append("PACE PLANEJADO\nFaixa de 6:25 a 6:35 min/km. Priorize estabilizar a passada em ritmo confortável sem acelerar para compensar a largada.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ2 (125 a 139 bpm). Caso a FC ultrapasse 139 bpm por ansiedade, reduza levemente a passada até a estabilização.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nTrecho inicial plano de asfalto urbano. Aproveite a ausência de aclives para estabelecer cadência estável.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nHidratação preventiva nos postos com pequenos goles de água pura (100-150 ml) sem interromper a passada.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nFC estabilizada em Z2, respiração ritmada e sensação de esforço leve a moderado.\n\n");

            sb.append("FASE 2 — KM 3 AO KM 6\n");
            sb.append("OBJETIVO DO TRECHO\nManutenção do ritmo de base aeróbica em Z2 e gestão consciente do primeiro acidente altimétrico do percurso.\n");
            sb.append("PACE PLANEJADO\nFaixa de 6:15 a 6:25 min/km no plano. Aceite perda momentânea de velocidade na subida para preservar glicogênio.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ2 (125 a 139 bpm). Gerencie o esforço pela FC durante o aclive para evitar picos precoces de acidose.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nSubida da Ponte Rio Poxim no Km 5.2 (+9.7m de elevação). Encurte a passada, aumente a cadência e use a descida para soltar as pernas.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nConsumo do 1º Gel de Carboidrato (20g CHO) no Km 6, obrigatoriamente acompanhado de 150-200 ml de água no posto de hidratação.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nRecuperação completa da FC para Z2 após a descida da ponte e tolerância gastrointestinal perfeita ao gel.\n\n");

            sb.append("FASE 3 — KM 6 AO KM 9\n");
            sb.append("OBJETIVO DO TRECHO\nEstabilização no ritmo alvo de cruzeiro na Av. Beira Mar, consolidando a eficiência metabólica em Z2.\n");
            sb.append("PACE PLANEJADO\nFaixa de 6:10 a 6:20 min/km. Mantenha a regularidade do ritmo aproveitando a topografia favorável.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ2 (125 a 139 bpm). Se a FC indicar elevação contínua sem ganho de velocidade, reduza 5 segundos no pace.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nTrecho totalmente plano da Av. Beira Mar. Foco na postura, relaxamento dos ombros e economia de corrida.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nManutenção da hidratação com água nos postos disponíveis a cada 2 km ou 3 km conforme necessidade térmica.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nAusência de desacoplamento cardiovascular relevante e bom acoplamento neuromuscular mantido.\n\n");

            sb.append("FASE 4 — KM 9 AO KM 12\n");
            sb.append("OBJETIVO DO TRECHO\nTransição gradual para Z3, administrando as variações altimétricas da região da Farolândia sem comprometer o final.\n");
            sb.append("PACE PLANEJADO\nFaixa de 6:05 a 6:15 min/km. Ajuste o esforço nos aclives e busque regularidade imediata no plano.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nTransição Z2 para Z3 (138 a 145 bpm). Monitore a resposta tensional antes de autorizar aumento do ritmo.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nRetorno da Farolândia (+8.8m no Km 9.6) e Viaduto Maria do Carmo (+8.8m no Km 11.5). Controle a amplitude de passada na subida.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nGoles curtos de água nos postos para manter o equilíbrio hídrico e preencher o estômago antes do segundo gel.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nCapacidade sustentada de manter o ritmo em Z3 sem sensação de estafa muscular precoce.\n\n");

            sb.append("FASE 5 — KM 12 AO KM 15\n");
            sb.append("OBJETIVO DO TRECHO\nSustentação de ritmo firme em Z3 e superação do trecho crítico do percurso com estratégia nutricional alinhada.\n");
            sb.append("PACE PLANEJADO\nFaixa de 6:00 a 6:10 min/km. Progrida o ritmo com segurança caso a resposta metabólica permaneça positiva.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ3 (140 a 152 bpm). Aceite a subida da FC compatível com a intensidade, evitando ultrapassar 155 bpm nesta fase.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nPonte Godofredo Diniz (+10.5m de elevação no Km 14.7) seguida do plano do Parque da Sementeira / Riomar.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nConsumo do 2º Gel de Carboidrato (20g CHO) no Km 13 no plano, acompanhado obrigatoriamente por 150-200 ml de água pura.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nAbsorção adequada dos carboidratos e estabilidade muscular para iniciar a progressão final de prova.\n\n");

            sb.append("FASE 6 — KM 15 AO KM 18\n");
            sb.append("OBJETIVO DO TRECHO\nIntensificação em Z3 Superior na Av. Delmiro Gouveia, aplicando o Negative Split caso haja reserva funcional suficiente.\n");
            sb.append("PACE PLANEJADO\nFaixa de 5:55 a 6:05 min/km. Aceleração progressiva e consciente sustentada no plano.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ3 Superior (148 a 155 bpm). Se houver sinais de fadiga excessiva ou dor, mantenha um esforço sustentável em Z3.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nPlano contínuo na Av. Delmiro Gouveia. Terreno ideal para imprimir ritmo constante de velocidade.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nHidratação de apoio com água para enxágue bucal e conforto térmico nos pontos de suporte.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nFoco mental elevado, ausência de cãibras e margem cardiovascular para o ataque final na Orla.\n\n");

            sb.append("FASE 7 — KM 18 ATÉ A CHEGADA, NO KM 21,097\n");
            sb.append("OBJETIVO DO TRECHO\nAproveitamento da supercompensação do polimento na Orla de Atalaia, acelerando com segurança até o pórtico de chegada.\n");
            sb.append("PACE PLANEJADO\nAbaixo de 5:55 min/km no trecho geral, com 100 metros finais livres de sprint para a linha de chegada.\n");
            sb.append("FREQUÊNCIA CARDÍACA\nZ3 Superior / Z4 sustentado (155 a 165+ bpm). Aceleração sujeita ao estado muscular e cardiovascular real do atleta.\n");
            sb.append("PREPARAÇÃO PARA A ALTIMETRIA\nTrecho plano da Orla de Atalaia até a reta final de chegada. Ventos laterais moderados podem exigir foco na postura.\n");
            sb.append("NUTRIÇÃO E HIDRATAÇÃO\nGoles pontuais de água se necessário para alívio do ressecamento bucal nos quilômetros finais.\n");
            sb.append("CRITÉRIO PARA A PRÓXIMA FASE\nConclusão oficial dos 21,097 km da prova com travessia segura da linha de chegada e desaceleração gradual.</method>\n");
            sb.append("    <justification>Prescrição oficial da prova alvo ajustada pela capacidade de endurance, perfil de altimetria e pela média histórica de eficiência recuperada do MySQL.</justification>\n");
            sb.append("    <target_scenario>1</target_scenario>\n");
            sb.append("    <target_level>5</target_level>\n");
            sb.append("  </prescription>\n");
        } else {
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
        }

        // 🎯 OBRIGATÓRIO: NÓ DE CONCLUSÃO E MOTIVAÇÃO
        sb.append("  <weekly_conclusion>")
                .append("Escreva um texto motivador de 3 a 5 linhas parabenizando o atleta ")
                .append(user.getName())
                .append(" pela dedicação contínua no ciclo de treinos, fazendo um breve resumo da sua prontidão para a Meia Maratona e desejando excelente prova com muita energia e foco!")
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

            String rawMethod = extractTagValue(block, "method");
            String justification = extractTagValue(block, "justification");

            String methodCompleto = "";
            if (rawMethod != null && !rawMethod.isBlank()) {
                methodCompleto = rawMethod.replaceAll("[*#`]", "").trim();
                // Formatação defensiva caso o método venha em parágrafo único sem quebra
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

        // 🎯 EXTRAÇÃO E ANEXO DO BLOCO FINAL DE MOTIVAÇÃO E CONCLUSÃO
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
}