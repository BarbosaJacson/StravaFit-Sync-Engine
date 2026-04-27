package jackson.stravafit.repository;

import jackson.stravafit.model.ActivityEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<ActivityEntity, Long> {
    
    @Query("SELECT a FROM ActivityEntity a ORDER BY a.startDate DESC")
    List<ActivityEntity> findLastActivities(Pageable pageable);
}
