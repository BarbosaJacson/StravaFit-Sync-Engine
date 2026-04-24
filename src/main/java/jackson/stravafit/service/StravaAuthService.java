package jackson.stravafit.service;

import jackson.stravafit.model.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Service
public class StravaAuthService {

    @Value("${strava.client.id}")
    private String clientId;

    @Value("${strava.client.secret}")
    private String clientSecret;

    public TokenResponse refreshToken(String currentRefreshToken) {
        RestTemplate restTemplate = new RestTemplate();
        String url = "https://www.strava.com/api/v3/oauth/token";

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", currentRefreshToken);

        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(url, params, TokenResponse.class);
        return response.getBody();
    }
}