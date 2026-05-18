package jackson.stravafit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class KnowledgeService {

    private static final String SETTINGS_FILE = "studySettings.txt";

    /**
     * Recupera o conteúdo científico diretamente do arquivo físico no projeto.
     * Isso garante que a IA sempre tenha acesso aos dados, mesmo que o banco de dados falhe.
     */
    public String getScientificContext() {
        try {
            log.debug("[KNOWLEDGE] Lendo contexto científico de: {}", SETTINGS_FILE);
            ClassPathResource resource = new ClassPathResource(SETTINGS_FILE);

            if (!resource.exists()) {
                log.warn("[KNOWLEDGE] Arquivo {} não encontrado! Usando fallback genérico.", SETTINGS_FILE);
                return "Utilize diretrizes gerais de Biogênese Mitocondrial.";
            }

            try (InputStream is = resource.getInputStream()) {
                byte[] bytes = is.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("[KNOWLEDGE] Erro crítico ao ler o arquivo de configuração: {}", e.getMessage());
            return "Erro ao carregar base científica.";
        }
    }
}