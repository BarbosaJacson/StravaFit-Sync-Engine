package jackson.stravafit.repository;

import jackson.stravafit.model.WorkoutPrescriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface WorkoutPrescriptionRepository extends JpaRepository<WorkoutPrescriptionEntity, Long> {


    Optional<WorkoutPrescriptionEntity> findByActivityId(Long activityId);

    Optional<WorkoutPrescriptionEntity> findByUserIdAndScheduledDate(Long userId, LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findTopByUserIdAndScheduledDateOrderByCreatedAtDesc(Long userId, LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findTopByUserIdAndScheduledDateLessThanEqualOrderByScheduledDateDescCreatedAtDesc(Long userId, LocalDate scheduledDate);

    Optional<WorkoutPrescriptionEntity> findTopByUserIdAndTargetScenarioOrderByScheduledDateDescCreatedAtDesc(Long userId, Integer targetScenario);
}