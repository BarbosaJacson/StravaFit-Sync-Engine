package jackson.stravafit.repository;

import jackson.stravafit.model.ScenarioEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScenarioRepository extends MongoRepository<ScenarioEntity, String> {
}
