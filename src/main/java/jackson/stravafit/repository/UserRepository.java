package jackson.stravafit.repository;

import org.springframework.stereotype.Repository;
import jackson.stravafit.model.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;


@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity>findByEmail(String email);
    Optional<UserEntity>findByStravaAthleteId(Long stravaAthleteId);
    Optional<UserEntity>findByGoogleId(String googleId);


}
