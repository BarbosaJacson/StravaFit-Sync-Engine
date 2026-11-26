package jackson.stravafit.repository;

import jackson.stravafit.model.UserGoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserGoalRepository extends JpaRepository<UserGoalEntity, Long> {

    Optional<UserGoalEntity> findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(Long userId);

    List<UserGoalEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}