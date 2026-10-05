package jackson.stravafit.repository;

import jackson.stravafit.model.WorkoutPrescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WorkoutPrescriptionRepository extends JpaRepository<WorkoutPrescriptionEntity, Long> {

    Optional<WorkoutPrescriptionEntity> findByScheduledDate(LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateOrderByCreatedAtDesc(LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findTopByScheduledDateLessThanEqualOrderByScheduledDateDescCreatedAtDesc(LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findByActivityId(Long activityId);

    Optional<WorkoutPrescriptionEntity> findTopByTargetScenarioOrderByScheduledDateDescCreatedAtDesc(Integer targetScenario);
}