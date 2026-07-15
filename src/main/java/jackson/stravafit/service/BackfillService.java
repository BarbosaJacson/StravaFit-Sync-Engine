package jackson.stravafit.service;

import jackson.stravafit.model.ActivitySummaryEntity;
import jackson.stravafit.repository.ActivitySummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillService {

    private final ActivitySummaryRepository activitySummaryRepository;

    public void reclassificarTreinosAntigos() {
        log.info("[BACKFILL] Iniciando reclassificação histórica de treinos por calendário de planilha...");

        List<ActivitySummaryEntity> todosSumarios = activitySummaryRepository.findAll();
        int atualizados = 0;

        for (ActivitySummaryEntity summary : todosSumarios) {
            try {
                java.time.DayOfWeek diaSemana = summary.getStartDate().getDayOfWeek();

                String tipoEstimulo;
                int cenarioDetectado;
                int nivelDetectado;

                if (diaSemana == java.time.DayOfWeek.THURSDAY) {
                    // Quinta-feira: Tiros
                    tipoEstimulo = "INTENSO / INTERVALADO (TIROS)";
                    cenarioDetectado = 2;
                    nivelDetectado = 1; // Base inicial para o histórico
                } else if (diaSemana == java.time.DayOfWeek.TUESDAY) {
                    // Terça-feira: Rodagem mais curta
                    tipoEstimulo = "CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)";
                    cenarioDetectado = 1;
                    nivelDetectado = 1; // Nível 1 para as terças
                } else if (diaSemana == java.time.DayOfWeek.SATURDAY) {
                    // Sábado: Longão
                    tipoEstimulo = "CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)";
                    cenarioDetectado = 1;
                    nivelDetectado = 2; // Nível 2 para os sábados
                } else {
                    // Outros dias (Caso existam treinos extras/exceções)
                    tipoEstimulo = "CONTÍNUO / ESTÁVEL (RODAGEM OU LONGÃO)";
                    cenarioDetectado = 1;
                    nivelDetectado = 1;
                }

                // Aplica a atualização se houver divergência técnica no banco
                if (!tipoEstimulo.equals(summary.getRealStimulusType())
                        || summary.getDetectedScenario() != cenarioDetectado
                        || summary.getDetectedLevel() != nivelDetectado) {

                    summary.setRealStimulusType(tipoEstimulo);
                    summary.setDetectedScenario(cenarioDetectado);
                    summary.setDetectedLevel(nivelDetectado);

                    activitySummaryRepository.save(summary);
                    atualizados++;
                    log.info("[DB BACKFILL] Ajustado ID {} ({}) para Cenário {}, Nível {}",
                            summary.getActivityId(), diaSemana, cenarioDetectado, nivelDetectado);
                }

            } catch (Exception e) {
                log.error("[BACKFILL] Erro ao classificar treino histórico ID {}: {}", summary.getActivityId(), e.getMessage());
            }
        }
        log.info("[BACKFILL] Concluído! {} treinos antigos recalibrados.", atualizados);
    }
}