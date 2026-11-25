package jackson.stravafit.controller;

import jackson.stravafit.model.UserEntity;
import jackson.stravafit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {
    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity getCurrentUser(@AuthenticationPrincipal OidcUser oidcUser) {
        if (oidcUser == null) {
            return ResponseEntity.status(401).build();
        }

        UserEntity user = userRepository.findByGoogleId(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado no banco de dados."));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", user.getId());
        response.put("googleId", user.getGoogleId());
        response.put("name", user.getName());
        response.put("email", user.getEmail());
        response.put("gender", user.getGender());
        response.put("birthDate", user.getBirthDate());
        response.put("hrMax", user.getHrMax());
        response.put("hrResting", user.getHrResting());
        response.put("weeklyFrequency", user.getWeeklyFrequency());
        response.put("trainingDays", user.getTrainingDays());
        response.put("targetObjective", user.getTargetObjective());
        response.put("stravaConnected", user.getStravaAthleteId() != null);

        return ResponseEntity.ok(response);
    }
}