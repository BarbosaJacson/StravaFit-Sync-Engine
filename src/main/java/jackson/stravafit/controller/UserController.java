package jackson.stravafit.controller;

import jackson.stravafit.model.UserEntity;
import jackson.stravafit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
        response.put("hrZoneSource", user.getHrZoneSource());
        response.put("z1MinBpm", user.getZ1MinBpm());
        response.put("z1MaxBpm", user.getZ1MaxBpm());
        response.put("z2MinBpm", user.getZ2MinBpm());
        response.put("z2MaxBpm", user.getZ2MaxBpm());
        response.put("z3MinBpm", user.getZ3MinBpm());
        response.put("z3MaxBpm", user.getZ3MaxBpm());
        response.put("z4MinBpm", user.getZ4MinBpm());
        response.put("z4MaxBpm", user.getZ4MaxBpm());
        response.put("z5MinBpm", user.getZ5MinBpm());
        response.put("z5MaxBpm", user.getZ5MaxBpm());

        return ResponseEntity.ok(response);
    }

    @PutMapping("/me")
    public ResponseEntity updateCurrentUser(
            @AuthenticationPrincipal OidcUser oidcUser,
            @RequestBody Map<String, Object> payload){
        if (oidcUser == null) {
            return ResponseEntity.status(401).build();
        }

        UserEntity user = userRepository.findByGoogleId(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado no banco de dados."));

        if (payload.get("name") instanceof String name && !name.isBlank()) {
            user.setName(name.trim());
        }
        if (payload.get("email") instanceof String email && !email.isBlank()) {
            user.setEmail(email.trim());
        }
        if (payload.get("birthDate") instanceof String birthDateStr && !birthDateStr.isBlank()) {
            user.setBirthDate(LocalDate.parse(birthDateStr));
        }
        Integer weeklyFrequency = toInteger(payload.get("weeklyFrequency"));
        if (weeklyFrequency != null && weeklyFrequency >= 1 && weeklyFrequency <= 7) {
            user.setWeeklyFrequency(weeklyFrequency);
        }
        if (payload.get("trainingDays") instanceof String trainingDaysStr && !trainingDaysStr.isBlank()) {
            user.setTrainingDays(trainingDaysStr.trim().toUpperCase());
        }

        String source = (payload.get("hrZoneSource") instanceof String s) ? s.trim().toUpperCase() : "FORMULA";
        user.setHrZoneSource(source);

        if ("EXAM".equals(source)) {
            user.setZ1MinBpm(toInteger(payload.get("z1MinBpm")));
            user.setZ1MaxBpm(toInteger(payload.get("z1MaxBpm")));
            user.setZ2MinBpm(toInteger(payload.get("z2MinBpm")));
            user.setZ2MaxBpm(toInteger(payload.get("z2MaxBpm")));
            user.setZ3MinBpm(toInteger(payload.get("z3MinBpm")));
            user.setZ3MaxBpm(toInteger(payload.get("z3MaxBpm")));
            user.setZ4MinBpm(toInteger(payload.get("z4MinBpm")));
            user.setZ4MaxBpm(toInteger(payload.get("z4MaxBpm")));
            user.setZ5MinBpm(toInteger(payload.get("z5MinBpm")));
            user.setZ5MaxBpm(toInteger(payload.get("z5MaxBpm")));
            if (user.getZ5MaxBpm() != null) {
                user.setHrMax(user.getZ5MaxBpm());
            }
        } else {
            Integer hrMax = toInteger(payload.get("hrMax"));
            Integer hrResting = toInteger(payload.get("hrResting"));
            if (hrMax != null) user.setHrMax(hrMax);
            if (hrResting != null) user.setHrResting(hrResting);
        }

        userRepository.save(user);
        return getCurrentUser(oidcUser);
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            return Integer.parseInt(str.trim());
        }
        return null;
    }
}