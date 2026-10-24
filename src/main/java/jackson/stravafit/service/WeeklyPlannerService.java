package jackson.stravafit.service;

import jackson.stravafit.client.GeminiClient;
import jackson.stravafit.client.TelegramClient;
import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.model.UserEventEntity;
import jackson.stravafit.model.WorkoutPrescriptionEntity;
import jackson.stravafit.repository.ActivitySummaryRepository;
import jackson.stravafit.repository.UserEventRepository;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
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
    private final UserEventRepository userEventRepository;

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

        LocalDate inicio;
        if (hoje.getDayOfWeek() == DayOfWeek.SATURDAY || hoje.getDayOfWeek() == DayOfWeek.SUNDAY) {
            inicio = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } else {
            inicio = hoje.minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        LocalDateTime inicioSemana = inicio.atStartOfDay();
        LocalDateTime fimSemana = hoje.atTime(23, 59, 59);

        log.info("[WEEKLY PLANNER] Buscando treinos da semana para o resumo entre {} e {}", inicioSemana, fimSemana);

        List<ActivitySummaryEntity> treinos = activitySummaryRepository
                .findByStartDateBetweenOrderByStartDateAsc(inicioSemana, fimSemana);

        if (treinos.isEmpty()) {
            log.warn("[WEEKLY PLANNER] Nenhum treino no intervalo {}-{}. Usando fallback das últimas 3 sessões do MySQL.", inicioSemana, fimSemana);
            treinos = activitySummaryRepository.findTop10ByDetectedScenarioOrderByStartDateDesc(1).stream().limit(3).toList();
        }

        return treinos;
    }

    public String gerarPrescricaoSemanalIA(UserEntity user, List<ActivitySummaryEntity> treinosAnteriores) {
        LocalDate hoje = LocalDate.now(ZONE_SP);

        LocalDate proximaSegunda;
        if (hoje.getDayOfWeek() == DayOfWeek.SATURDAY || hoje.getDayOfWeek() == DayOfWeek.SUNDAY) {
            proximaSegunda = hoje.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        } else {
            proximaSegunda = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

// Para garantir que a projeção da semana comece sempre a partir do dia seguinte ao sábado:
        LocalDate terca  = proximaSegunda.with(DayOfWeek.TUESDAY);
        LocalDate quinta = proximaSegunda.with(DayOfWeek.THURSDAY);
        LocalDate sabado = proximaSegunda.with(DayOfWeek.SATURDAY);

        // CÁLCULO DAS SEMANAS RESTANTES PARA O EVENTO PRINCIPAL
        LocalDate dataProvaAlvo = userEventRepository.findByUserIdAndIsMainEventTrue(user.getId())
                .map(UserEventEntity::getEventDate)
                .orElse(LocalDate.of(2026, 10, 31));

        long diasParaProva = ChronoUnit.DAYS.between(hoje, dataProvaAlvo);
        long semanasParaProva = Math.max(1, (diasParaProva / 7) + 1);

        // 1. Histórico recente de cada cenário no MySQL
        List<ActivitySummaryEntity> listaTiros = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(2);
        List<ActivitySummaryEntity> listaCenario1 = activitySummaryRepository
                .findTop10ByDetectedScenarioOrderByStartDateDesc(1);

        // 2. Filtra os últimos 5 treinos específicos de cada dia
        List<ActivitySummaryEntity> ultimasTercas = listaCenario1.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.TUESDAY)
                .limit(5).toList();

        List<ActivitySummaryEntity> ultimasQuintas = listaTiros.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.THURSDAY)
                .limit(5).toList();

        List<ActivitySummaryEntity> ultimosSabados = listaCenario1.stream()
                .filter(a -> a.getStartDate() != null && a.getStartDate().getDayOfWeek() == DayOfWeek.SATURDAY)
                .limit(5).toList();
        int nivelVigenteCenario1 = workoutPrescriptionRepository
                .findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(1)
                .map(WorkoutPrescriptionEntity::getTargetLevel)
                .orElse(1);

        int nivelVigenteCenario2 = workoutPrescriptionRepository
                .findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(2)
                .map(WorkoutPrescriptionEntity::getTargetLevel)
                .orElse(1);

        // 3. Média de Efficiency Index das 5 sessões de cada dia
        double mediaEI_Terca = ultimasTercas.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);
        double mediaEI_Quinta = ultimasQuintas.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);
        double mediaEI_Sabado = ultimosSabados.stream().mapToDouble(ActivitySummaryEntity::getEfficiencyIndex).average().orElse(1.00);


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
        sb.append("--- DIRETRIZES DE AVALIAÇÃO DE NÍVEL E PRESCRIÇÃO (SISTEMA DE DECISÃO IA) ---\n");
        sb.append("1. CONSULTA MANDATÓRIA AO MONGODB:\n");
        sb.append("   - Para cada dia prescrito, você DEVE consultar os documentos JSON 'cenario_1_aerobico' e 'cenario_2_anaerobico_tiros' fornecidos acima.\n");
        sb.append("   - Localize o Nível ativo indicado para o dia, varra a lista 'variacoes_estimulo' e selecione a variação EXATA cujo parâmetro 'criterio_ia' contemple a média de Efficiency Index (EI) calculada pelo sistema.\n");
        sb.append("   - Extraia rigorosamente do JSON selecionado: 'type', 'distance_km', 'duration', 'intensity', 'focus' e 'method' (detalhando tiros/pausas se for Cenário 2).\n\n");

        sb.append("--- DIRETRIZES DE AVALIAÇÃO DE NÍVEL E PRESCRIÇÃO (SISTEMA DE DECISÃO IA) ---\n");
        sb.append("1. CONSULTA MANDATÓRIA AO MONGODB:\n");
        sb.append("   - Para cada dia prescrito, você DEVE consultar os documentos JSON 'cenario_1_aerobico' e 'cenario_2_anaerobico_tiros' fornecidos acima.\n");
        sb.append("   - Localize o Nível ativo indicado para o dia, varra a lista 'variacoes_estimulo' e selecione a variação EXATA cujo parâmetro 'criterio_ia' contemple a média de Efficiency Index (EI) calculada pelo sistema.\n");
        sb.append("   - Extraia rigorosamente do JSON selecionado: 'type', 'distance_km', 'duration', 'intensity', 'focus' e 'method' (detalhando tiros/pausas se for Cenário 2).\n\n");

        sb.append("--- DIRETRIZES DE DECISÃO E SELEÇÃO DE NÍVEL (CONSULTA DINÂMICA AO JSON) ---\n");
        sb.append("Para cada dia prescrito, você deve consultar os documentos JSON 'cenario_1_aerobico' e 'cenario_2_anaerobico_tiros' e selecionar o NÍVEL e VARIAÇÃO correspondentes avaliando as regras de 'criterio_ia' e 'gatilho_promocao' com base no Efficiency Index médio calculado pelo sistema:\n\n");

        sb.append("--- ESTADO DE PROGRESSÃO E NÍVEIS ATUAIS (MYSQL -> MONGODB) ---\n");
        sb.append("O atleta encontra-se atualmente no MySQL com os seguintes níveis ativos:\n");
        sb.append("- Cenário 1 (Rodagens/Longão Z2): NÍVEL ").append(nivelVigenteCenario1).append("\n");
        sb.append("- Cenário 2 (Tiros VO2máx): NÍVEL ").append(nivelVigenteCenario2).append("\n\n");

        sb.append("--- ESTADO DE PROGRESSÃO E NÍVEIS ATUAIS (MYSQL -> MONGODB) ---\n");
        sb.append("O atleta possui o seguinte histórico de níveis ativos gravados no MySQL:\n");
        sb.append("- Cenário 1 (Rodagens/Longão Z2): NÍVEL ").append(nivelVigenteCenario1).append("\n");
        sb.append("- Cenário 2 (Tiros VO2máx): NÍVEL ").append(nivelVigenteCenario2).append("\n\n");

        sb.append("--- MÉDIAS DE EFFICIENCY INDEX (ÚLTIMAS 5 SESSÕES) ---\n");
        sb.append("- Terça-feira (Cenário 1 - Rodagem Leve): ").append(String.format("%.3f", mediaEI_Terca)).append("\n");
        sb.append("- Quinta-feira (Cenário 2 - Tiros Z3/Z4): ").append(String.format("%.3f", mediaEI_Quinta)).append("\n");
        sb.append("- Sábado (Cenário 1 - Longão Z2): ").append(String.format("%.3f", mediaEI_Sabado)).append("\n\n");

        sb.append("--- DIRETRIZES EXCLUSIVAS DE PRESCRIÇÃO E REGRAS DE NÍVEL ---\n");
        sb.append("1. TERÇA-FEIRA (Rodagem Leve / FatMax):\n");
        sb.append("   - Fisiologicamente TRAVADA no NÍVEL 1 do 'cenario_1_aerobico' (Teto máximo de 10.0 km para evitar sobrecarga no início da semana).\n");
        sb.append("   - Avalie o EI médio das Terças (").append(String.format("%.3f", mediaEI_Terca)).append(") dentro do Nível 1:\n");
        sb.append("     * Se EI >= 1.050: Selecione OBRIGATORIAMENTE a variação N1-V4 (Consolidação do Teto Base - 10.0 km).\n");
        sb.append("   - Defina a tag <target_level>1</target_level>.\n\n");

        sb.append("2. QUINTA-FEIRA (Tiros / VO2máx):\n");
        sb.append("   - Consulte o Nível Ativo do Cenário 2 no MySQL (Nível ").append(nivelVigenteCenario2).append(") no 'cenario_2_anaerobico_tiros'.\n");
        sb.append("   - Verifique na chave 'gatilho_promocao' se o EI médio (").append(String.format("%.3f", mediaEI_Quinta)).append(") promove o atleta para o próximo Nível.\n");
        sb.append("   - Selecione a variação exata em 'variacoes_estimulo' do nível resultante conforme a regra 'criterio_ia'.\n");
        sb.append("   - Defina a tag <target_level> com o número do nível selecionado (1 a 5).\n\n");

        sb.append("3. SÁBADO (Longão de Base / Z2):\n");
        sb.append("   - Consulte o Nível Ativo do Cenário 1 no MySQL (Nível ").append(nivelVigenteCenario1).append(") no 'cenario_1_aerobico'.\n");
        sb.append("   - Avalie se o EI médio dos Sábados (").append(String.format("%.3f", mediaEI_Sabado)).append(") e o histórico de sessões acionam o 'gatilho_promocao' para subir de nível (ex: N1 -> N2 -> N3 -> N4 -> N5).\n");
        sb.append("   - Mapeie a variação exata em 'variacoes_estimulo' extraindo volume, tempo e foco técnico do Longão.\n");
        sb.append("   - Defina a tag <target_level> com o número do nível selecionado (1 a 5).\n\n");

        sb.append("--- REGRA DE SELEÇÃO DE TREINO E PROMOÇÃO ---\n");
        sb.append("1. Para cada dia, consulte o JSON correspondente no MongoDB para o Nível Ativo do cenário.\n");
        sb.append("2. Verifique se o histórico do atleta atinge os critérios do 'gatilho_promocao' (ex: sessões consecutivas acima do limite de EI) para promover ao Nível subsequente.\n");
        sb.append("3. Dentro do Nível selecionado (atual ou promovido), busque em 'variacoes_estimulo' a variação cuja regra 'criterio_ia' atenda o EI médio fornecido.\n");
        sb.append("4. Preencha a tag <target_level> com o número exato do Nível (1 a 5) selecionado para gravação no MySQL.\n\n");
        sb.append("3. TRAVA DE INTEGRIDADE:\n");
        sb.append("   - É ESTRITAMENTE PROIBIDO inventar faixas de distância, tempos de tiro ou repetições que não constem expressamente nas estruturas 'variacoes_estimulo' do MongoDB para o nível selecionado.\n\n");

        sb.append("1. CONSULTA AO MACROCICLO DA MEIA MARATONA ('macro_ciclo_meia_maratona_2026'):\n");
        sb.append("   • Com base nas ").append(semanasParaProva).append(" semanas restantes para o evento de ").append(dataProvaAlvo).append(":\n");
        sb.append("     - Identifique a chave de fase correspondente no JSON ('semanas_restantes_4_a_3' ou 'semanas_restantes_2_a_1').\n");
        sb.append("     - Aplique obrigatoriamente as orientações de 'foco_longao' / 'foco_volume', 'fortalecimento_e_prevencao' e 'cuidados_fisiologicos' da fase ativa no campo <focus> e <method> de cada prescrição.\n");
        sb.append("     - Na tag <weekly_overview>, faça a síntese do desempenho da semana anterior do MySQL correlacionando diretamente o estado do atleta com a fase do macrociclo descrita no documento.\n\n");

        boolean ehSemanaDaProva = sabado.equals(dataProvaAlvo);

        sb.append("--- REGRA ESPECIAL DE PROVA ALVO (MEIA MARATONA - 31/10/2026) ---\n");
        sb.append("Consulte o documento chave 'evento_meia_maratona_2026' na base de conhecimento do MongoDB fornecida acima.\n\n");
        if (ehSemanaDaProva) {
            sb.append("🚨 ATENÇÃO IA - GATILHO DE PROVA ATIVADO PARA SÁBADO (31/10/2026):\n");
            sb.append("1. O próximo sábado NÃO é um treino comum. É o dia da MEIA MARATONA OFICIAL (21.1 km) do atleta Jacson Barbosa.\n");
            sb.append("2. SUBSTITUA o treino convencional do 'cenario_1_aerobico' pela prescrição tática da prova.\n");
            sb.append("3. CONSULTA OBRIGATÓRIA AO MYSQL (activity_performance_summary):\n");
            sb.append("   - Analise os treinos de Longão (Sábados) do MySQL e extraia a média do Efficiency Index (EI) e o Pace Médio das melhores sessões em Nível 4 e 5.\n");
            sb.append("   - Calcule o Pace Alvo de Prova recomendado (min/km) e o tempo total estimado de prova para cobrir os 21.097 km.\n");
            sb.append("4. PREENCHIMENTO DA TAG <prescription> DO SÁBADO (31/10/2026):\n");
            sb.append("   - <type>PROVA OFICIAL - MEIA MARATONA (21.1 KM)</type>\n");
            sb.append("   - <distance_km>21.1</distance_km>\n");
            sb.append("   - <duration>[Informe o tempo total estimado calculado ex: 130 a 135 minutos]</duration>\n");
            sb.append("   - <intensity>Gestão Z2 no início (125-139 bpm) progredindo para Z3/Z4 nos km finais</intensity>\n");
            sb.append("   - <focus>Execução do plano de prova de 21.1 km. Controle rigoroso de pacing, hidratação constante, gestão de altimetria nas pontes/viadutos e reserva funcional para os km 15 a 21.</focus>\n");
            sb.append("   - <method>Detalhar a estratégia fracionando a prova em trechos de 3 km, analisando a progressão do ritmo e FC a cada bloco e incorporando os acidentes altimétricos da rota: Km 0-3 em Z2 pura para aclimação; Km 3-6 em Z2 (atenção ao Km 5 na subida da Ponte Rio Poxim com +10m de elevação - gerenciar esforço por FC para evitar picos precoces); Km 6-9 em Z2 no plano mantendo o Pace Alvo; Km 9-12 em Z2/Z3 administrando as variações dos viadutos e pontes da Farolândia; Km 12-15 em Z3 no trecho plano do Parque da Sementeira/Riomar; Km 15-18 e 18-21 em Z3/Z4 sustentado aproveitando a supercompensação do polimento, com 100m finais livres para o pórtico de chegada.</method>\n");
            sb.append("   - <nutrition_details>Estratégia de suplementação intra-prova (10g-20g CHO/h): Consumir o 1º Gel de Carboidrato no Km 6 (logo após a descida da Ponte Rio Poxim) e o 2º Gel no Km 13 (no plano do Parque da Sementeira). Obrigatoriamente ingerir ambos acompanhados de 150-200ml de água pura no posto de hidratação para garantir a taxa de esvaziamento gástrico e rápida absorção de glicose.</nutrition_details>\n");
            sb.append("   - <justification>Prescrição oficial da prova alvo ajustada pela capacidade de endurance, perfil de altimetria e pela média histórica de eficiência recuperada do MySQL.</justification>\n");
            sb.append("   - <conclusion_note>Com base na evolução do Efficiency Index (médias de 1.100+ em Z2/Z4), no acoplamento cardiovascular e na disciplina impecável do atleta Jacson Barbosa nos treinos, a preparação está consolidada — confiança total para fazer uma grande prova!</conclusion_note>\n");
            sb.append("   - <target_scenario>1</target_scenario>\n");
                        sb.append("   - <target_level>5</target_level>\n\n");
        } else {
            sb.append("Sábado normal de treino. Seguir as diretrizes padrão de progressão de Nível em 'cenario_1_aerobico'.\n\n");
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
        sb.append("    <target_level>[Número do Nível de 1 a 5 identificado dinamicamente no MongoDB para este EI]</target_level>\n");
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
        sb.append("    <target_level>[Número do Nível de 1 a 5 identificado dinamicamente no MongoDB para este EI]</target_level>\n");
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
        sb.append("    <target_level>[Número do Nível de 1 a 5 identificado dinamicamente no MongoDB para este EI]</target_level>\n");
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