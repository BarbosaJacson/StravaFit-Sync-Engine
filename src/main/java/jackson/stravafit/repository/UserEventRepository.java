package jackson.stravafit.repository;

import jackson.stravafit.model.UserEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserEventRepository extends JpaRepository<UserEventEntity, Long> {

    Optional<UserEventEntity> findByUserIdAndIsMainEventTrue(Long userId);
}
