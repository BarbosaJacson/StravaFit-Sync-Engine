package jackson.stravafit.repository;

import jackson.stravafit.model.ActivitySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ActivitySummaryRepository extends JpaRepository<ActivitySummaryEntity, Long> {

}