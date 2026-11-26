package jackson.stravafit.service;

import jackson.stravafit.model.*;
import jackson.stravafit.repository.UserEventRepository;
import jackson.stravafit.repository.UserGoalRepository;
import jackson.stravafit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserGoalService {

    private final UserRepository userRepository;
    private final UserGoalRepository userGoalRepository;
    private final UserEventRepository userEventRepository;
    private final TrainingStyleService trainingStyleService;

    @Transactional
    public UserGoalEntity defineActiveGoal(Long userId, GoalCategory category, String eventName, LocalDate eventDate) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado: " + userId));

        List<UserGoalEntity> existingGoals = userGoalRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (UserGoalEntity goal : existingGoals) {
            if (Boolean.TRUE.equals(goal.getActive())) {
                goal.setActive(false);
                userGoalRepository.save(goal);
            }
        }

        UserEventEntity linkedEvent = null;
        boolean hasEventName = eventName != null && !eventName.isBlank();
        if (hasEventName || eventDate != null) {
            if (!hasEventName || eventDate == null) {
                throw new IllegalArgumentException("Informe tanto o nome quanto a data do evento.");
            }
            linkedEvent = new UserEventEntity();
            linkedEvent.setUser(user);
            linkedEvent.setEventName(eventName.trim());
            linkedEvent.setEventDate(eventDate);
            linkedEvent = userEventRepository.save(linkedEvent);
        }
        UserGoalEntity newGoal = new UserGoalEntity();
        newGoal.setUser(user);
        newGoal.setCategory(category);
        newGoal.setTrainingStyleCode(category.getTrainingStyleCode());
        newGoal.setEvent(linkedEvent);
        newGoal.setActive(true);

        return userGoalRepository.save(newGoal);
    }

    public Optional<UserGoalEntity> getActiveGoal(Long userId) {
        return userGoalRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
    }

    public String getActiveStyleContext(UserEntity user) {
        if (user == null) {
            return trainingStyleService.resolveStyleContext(null, null);
        }
        String styleCode = (user.getId() != null)
                ? getActiveGoal(user.getId())
                .map(UserGoalEntity::getTrainingStyleCode)
                .orElse(GoalCategory.ROUTINE_CONSISTENCY.getTrainingStyleCode())
                : GoalCategory.ROUTINE_CONSISTENCY.getTrainingStyleCode();
        String genderStr = (user.getGender() != null) ? user.getGender().name() : null;
        return trainingStyleService.resolveStyleContext(styleCode, genderStr);
    }
}