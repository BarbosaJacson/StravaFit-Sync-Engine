package jackson.stravafit.controller;

import jackson.stravafit.model.GoalCategory;
import jackson.stravafit.model.UserEntity;
import jackson.stravafit.model.UserGoalEntity;
import jackson.stravafit.repository.UserRepository;
import jackson.stravafit.service.UserGoalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class UserGoalController {

    private final UserRepository userRepository;
    private final UserGoalService userGoalService;

    @GetMapping("/active")
    public ResponseEntity getActiveGoal(@AuthenticationPrincipal OidcUser oidcUser) {
        if (oidcUser == null) {
            return ResponseEntity.status(401).build();
        }

        UserEntity user = userRepository.findByGoogleId(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado no banco de dados."));

        Optional<UserGoalEntity> activeGoalOpt = userGoalService.getActiveGoal(user.getId());
        GoalCategory category = activeGoalOpt
                .map(UserGoalEntity::getCategory)
                .orElse(GoalCategory.ROUTINE_CONSISTENCY);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hasActiveGoal", activeGoalOpt.isPresent());
        body.put("category", category.name());
        body.put("categoryLabel", category.getLabel());
        body.put("trainingStyleCode", activeGoalOpt
                .map(UserGoalEntity::getTrainingStyleCode)
                .orElse(category.getTrainingStyleCode()));
        body.put("recommendedWeeklyFrequency", userGoalService.recommendWeeklyFrequency(category));
        body.put("currentWeeklyFrequency", user.getWeeklyFrequency());
        body.put("trainingDays", user.getTrainingDays());

        if (activeGoalOpt.isPresent() && activeGoalOpt.get().getEvent() != null) {
            body.put("eventName", activeGoalOpt.get().getEvent().getEventName());
            body.put("eventDate", activeGoalOpt.get().getEvent().getEventDate());
            body.put("targetDistanceKm", activeGoalOpt.get().getEvent().getTargetDistanceKm());
        }

        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<?> setActiveGoal(@AuthenticationPrincipal OidcUser oidcUser,
                                           @RequestBody Map<String, Object> payload) {
        if (oidcUser == null) {
            return ResponseEntity.status(401).build();
        }

        UserEntity user = userRepository.findByGoogleId(oidcUser.getSubject())
                .orElseThrow(() -> new IllegalStateException("Usuário autenticado não encontrado no banco de dados."));

        Object categoryObj = payload.get("category");
        if (!(categoryObj instanceof String categoryStr) || categoryStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe a categoria do objetivo (category)."));
        }

        GoalCategory category;
        try {
            category = GoalCategory.valueOf(categoryStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Categoria de objetivo inválida: " + categoryStr));
        }

        String eventName = (payload.get("eventName") instanceof String s && !s.isBlank()) ? s.trim() : null;
        LocalDate eventDate = null;
        if (payload.get("eventDate") instanceof String dateStr && !dateStr.isBlank()) {
            try {
                eventDate = LocalDate.parse(dateStr.trim());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Data do evento inválida. Use o formato AAAA-MM-DD."));
            }
        }

        try {
            UserGoalEntity saved = userGoalService.defineActiveGoal(user.getId(), category, eventName, eventDate);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", saved.getId());
            resp.put("category", saved.getCategory().name());
            resp.put("categoryLabel", saved.getCategory().getLabel());
            resp.put("trainingStyleCode", saved.getTrainingStyleCode());
            resp.put("recommendedWeeklyFrequency", userGoalService.recommendWeeklyFrequency(saved.getCategory()));
            if (saved.getEvent() != null) {
                resp.put("eventName", saved.getEvent().getEventName());
                resp.put("eventDate", saved.getEvent().getEventDate());
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}