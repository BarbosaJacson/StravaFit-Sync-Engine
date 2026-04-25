package jackson.stravafit.client;
import jackson.stravafit.model.StravaActivity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class StravaClient {

    private final RestClient restClient;

    public StravaClient(RestClient.Builder builder) {
        // Criamos o cliente com a URL base completa
        this.restClient = builder.baseUrl("https://www.strava.com").build();
    }

    public List<StravaActivity> getActivities(String token, int page) {
        List<StravaActivity> activities = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/athlete/activities")
                        .queryParam("per_page", 200)
                        .queryParam("page", page)
                        .build())
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<List<StravaActivity>>() {
                });
        
        return activities != null ? activities : List.of();
    }

    public List<StravaActivity.HeartRateZone> getActivityZones(String token, Long activityId) {
        return restClient.get()
                .uri("/api/v3/activities/{id}/zones", activityId)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<List<StravaActivity.HeartRateZone>>() {});
    }

    /**
     * Busca fluxos de dados (tempo, batimento, velocidade). 
     * O Strava retorna arrays de dados sincronizados por tempo.
     */
    public List<StravaActivity.ActivityStream> getActivityStreams(String token, Long activityId) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v3/activities/{id}/streams")
                        .queryParam("keys", "time,heartrate,velocity_smooth")
                        .build(activityId))
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<List<StravaActivity.ActivityStream>>() {});
    }
}