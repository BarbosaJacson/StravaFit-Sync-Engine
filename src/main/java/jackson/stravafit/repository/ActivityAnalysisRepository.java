package jackson.stravafit.repository;

import jackson.stravafit.model.ActivityAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActivityAnalysisRepository extends JpaRepository<ActivityAnalysisEntity, Long> {

    Optional<ActivityAnalysisEntity> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
    boolean existsByStravaActivityId(Long stravaActivityId);
}
