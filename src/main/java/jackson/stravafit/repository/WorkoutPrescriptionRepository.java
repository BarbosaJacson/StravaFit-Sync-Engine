package jackson.stravafit.repository;

import jackson.stravafit.model.WorkoutPrescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WorkoutPrescriptionRepository extends JpaRepository<WorkoutPrescriptionEntity, Long> {

    // Busca a última prescrição para uma data específica
    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateOrderByCreatedAtDesc(LocalDate scheduledDate);

    // Busca a última prescrição gerada, independente da data
    //Optional<WorkoutPrescriptionEntity> findTopByOrderByCreatedAtDesc();

    // Busca a prescrição mais recente agendada até a data da atividade (<= scheduledDate)
    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateLessThanEqualOrderByScheduledDateDescCreatedAtDesc(LocalDate scheduledDate);
    
    // Adicionado para a lógica de UPSERT no InsightService
    Optional<WorkoutPrescriptionEntity> findByActivityId(Long activityId);
}
