package jackson.stravafit.service;

import jackson.stravafit.model.UserEntity;
import jackson.stravafit.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleOAuthUserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String googleId = oidcUser.getSubject();
        String email = oidcUser.getEmail();
        String name = oidcUser.getFullName() != null ? oidcUser.getFullName() : email;

        UserEntity user = userRepository.findByGoogleId(googleId)
                .or(() -> userRepository.findByEmail(email))
                .map(existing -> {
                    if (existing.getGoogleId() == null) {
                        log.info("[AUTH-GOOGLE] Vinculando google_id ao usuário existente: {} (ID: {})", email, existing.getId());
                        existing.setGoogleId(googleId);
                    }
                    return userRepository.save(existing);
                })
                .orElseGet(() -> {
                    log.info("[AUTH-GOOGLE] Criando novo atleta via Google OAuth: {}", email);
                    UserEntity newUser = new UserEntity();
                    newUser.setGoogleId(googleId);
                    newUser.setEmail(email);
                    newUser.setName(name);
                    newUser.setHrMax(180);
                    newUser.setHrResting(60);
                    return userRepository.save(newUser);
                });

        log.info("[AUTH-GOOGLE] Login autenticado com sucesso para UserEntity ID: {}", user.getId());
        return oidcUser;
    }
}