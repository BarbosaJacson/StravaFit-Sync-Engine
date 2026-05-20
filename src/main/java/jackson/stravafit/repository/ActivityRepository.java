package jackson.stravafit.repository;

import jackson.stravafit.model.ActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<ActivityEntity, Long> {

    // O próprio nome do método já define a query Top 10 com ordenação para o Spring Data JPA
    List<ActivityEntity> findTop10ByOrderByStartDateDesc();
}
