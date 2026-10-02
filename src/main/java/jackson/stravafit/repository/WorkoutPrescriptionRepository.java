package jackson.stravafit.repository;

import jackson.stravafit.model.WorkoutPrescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WorkoutPrescriptionRepository extends JpaRepository<WorkoutPrescriptionEntity, Long> {

    /**
     * Busca a prescrição exatamente agendada para a data informada (Prioridade 1 no InsightService).
     */
    Optional<WorkoutPrescriptionEntity> findByScheduledDate(LocalDate scheduledDate);

    /**
     * Busca a prescrição mais recente agendada para uma data específica, ordenando pela criação.
     */
    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateOrderByCreatedAtDesc(LocalDate scheduledDate);

    /**
     * Fallback: Busca a prescrição mais recente agendada até a data informada (<= scheduledDate).
     * Ordena primariamente pela data agendada decrescente e secundariamente pela data de criação.
     */
    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateLessThanEqualOrderByScheduledDateDescCreatedAtDesc(LocalDate scheduledDate);

    /**
     * Busca a prescrição associada a uma atividade do Strava específica (Upsert/Mapeamento).
     */
    Optional<WorkoutPrescriptionEntity> findByActivityId(Long activityId);

    /**
     * Busca a prescrição mais recente de um cenário específico (Cenário 1 = Rodagem/Z2, Cenário 2 = Tiros).
     */
    Optional<WorkoutPrescriptionEntity> findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(Integer targetScenario);
}