package jackson.stravafit.repository;

import jackson.stravafit.model.ActivitySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ActivitySummaryRepository extends JpaRepository<ActivitySummaryEntity, Long> {

    // 🎯 Consulta SQL nativa para buscar os últimos 10 treinos de uma determinada zona no seu MySQL da Aiven
    @Query(value = "SELECT * FROM activity_performance_summary WHERE dominant_zone = :dominantZone ORDER BY start_date DESC LIMIT 10", nativeQuery = true)
    List<ActivitySummaryEntity> findTop10ByDominantZoneOrderByStartDateDesc(@Param("dominantZone") int dominantZone);
}