package jackson.stravafit.controller;

import jackson.stravafit.model.TokenResponse;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.repository.UserRepository;
import jackson.stravafit.service.StravaAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/strava")
@RequiredArgsConstructor
public class StravaAuthController {

    private final UserRepository userRepository;
    private final StravaAuthService stravaAuthService;

    @GetMapping("/connect")
    public ResponseEntity<Void> connectStrava(@AuthenticationPrincipal OidcUser oidcUser,
                                              HttpSession session) {
        if (oidcUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UserEntity user = userRepository.findByGoogleId(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado."));

        String state = UUID.randomUUID().toString();
        session.setAttribute("STRAVA_OAUTH_STATE", state);
        session.setAttribute("STRAVA_CONNECT_USER_ID", user.getId());

        String redirectUri = "http://localhost:8080/api/strava/callback";
        String authUrl = stravaAuthService.buildAuthorizationUrl(redirectUri, state);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(authUrl))
                .build();
    }
    @GetMapping("/callback")
    public ResponseEntity<String> stravaCallback(@RequestParam(required = false) String code,
                                                 @RequestParam(required = false) String state,
                                                 @RequestParam(required = false) String error,
                                                 HttpSession session) {
        if (error != null && !error.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Autorização no Strava negada ou cancelada: " + error);
        }

        String expectedState = (String) session.getAttribute("STRAVA_OAUTH_STATE");
        Long userId = (Long) session.getAttribute("STRAVA_CONNECT_USER_ID");

        session.removeAttribute("STRAVA_OAUTH_STATE");
        session.removeAttribute("STRAVA_CONNECT_USER_ID");

        if (code == null || code.isBlank() || state == null || expectedState == null || !expectedState.equals(state)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Parâmetros inválidos ou sessão de autorização expirada.");
        }

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Usuário da conexão Strava não identificado na sessão.");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Usuário não encontrado no banco: " + userId));

        TokenResponse tokenResponse = stravaAuthService.exchangeAuthorizationCode(code);

        user.setStravaAccessToken(tokenResponse.getAccessToken());
        if (tokenResponse.getRefreshToken() != null && !tokenResponse.getRefreshToken().isBlank()) {
            user.setStravaRefreshToken(tokenResponse.getRefreshToken());
        }
        user.setStravaTokenExpiresAt(tokenResponse.getExpiresAt());
        Long athleteId = stravaAuthService.fetchAthleteId(tokenResponse.getAccessToken());
        if (athleteId != null) {
            user.setStravaAthleteId(athleteId);
        }

        userRepository.save(user);
        return ResponseEntity.ok("Conta Strava conectada com sucesso para " + user.getEmail() + "!");
    }
}