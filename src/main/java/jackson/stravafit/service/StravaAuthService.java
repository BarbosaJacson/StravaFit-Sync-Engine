package jackson.stravafit.service;

import jackson.stravafit.model.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Service
public class StravaAuthService {

    @Value("${strava.client.id}")
    private String clientId;

    @Value("${strava.client.secret}")
    private String clientSecret;

    private final RestClient restClient;

    public StravaAuthService(RestClient.Builder builder) {
        this.restClient = builder.baseUrl("https://www.strava.com").build();
    }

    public TokenResponse refreshToken(String currentRefreshToken) {
        return restClient.post()
                .uri("/api/v3/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "client_id", clientId,
                        "client_secret", clientSecret,
                        "grant_type", "refresh_token",
                        "refresh_token", currentRefreshToken
                ))
                .retrieve()
                .body(TokenResponse.class);
    }
}