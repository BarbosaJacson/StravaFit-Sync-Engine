package jackson.stravafit.repository;

import jackson.stravafit.model.ActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository; // Certifique-se que este import existe
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityRepository extends JpaRepository<ActivityEntity, Long> {
}