package jackson.stravafit.client;
import jackson.stravafit.model.StravaActivity;
import org.springframework.core.ParameterizedTypeReference;
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

    public List<StravaActivity> getActivities(String token) {
        return restClient.get()
                .uri("/api/v3/athlete/activities?per_page=200") // Colocamos o caminho completo aqui
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<StravaActivity>>() {});
    }
}