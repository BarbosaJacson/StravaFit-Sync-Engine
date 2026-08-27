package jackson.stravafit.repository;

import jackson.stravafit.model.ActivitySummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ActivitySummaryRepository extends JpaRepository<ActivitySummaryEntity, Long> {

    // 🎯 Busca um sumário existente pelo ID da atividade do Strava (essencial para o Upsert/Update)
    Optional<ActivitySummaryEntity> findByActivityId(Long activityId);

    // 🎯 Consulta SQL nativa para buscar os últimos 10 treinos de uma determinada zona no seu MySQL da Aiven
    @Query(value = "SELECT * FROM activity_performance_summary WHERE dominant_zone = :dominantZone ORDER BY start_date DESC LIMIT 10", nativeQuery = true)
    List<ActivitySummaryEntity> findTop10ByDominantZoneOrderByStartDateDesc(@Param("dominantZone") int dominantZone);

    // 🎯 Consulta que traz os últimos 10 treinos de um cenário específico, independente do nível
    List<ActivitySummaryEntity> findTop10ByDetectedScenarioOrderByStartDateDesc(Integer detectedScenario);

    // Busca os sumários de treino realizados em um intervalo de datas (ex: Segunda a Domingo da semana passada)
    List<ActivitySummaryEntity> findByStartDateBetweenOrderByStartDateAsc(LocalDateTime start, LocalDateTime end);

}