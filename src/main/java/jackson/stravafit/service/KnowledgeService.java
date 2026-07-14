package jackson.stravafit.service;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeService {

    private final MongoTemplate mongoTemplate;

    // O Spring Boot injeta o MongoTemplate pré-configurado com a sua conexão do strava_fit_db
    public KnowledgeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String getScientificContext() {
        try {
            log.info("[MONGO] Buscando todos os documentos de inteligência na coleção 'scenarios'...");

            // Busca todos os documentos da coleção "scenarios" como Documents brutos do BSON
            List<Document> documentos = mongoTemplate.findAll(Document.class, "scenarios");

            if (documentos.isEmpty()) {
                log.warn("[MONGO] Nenhum documento foi encontrado na coleção 'scenarios'.");
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- BASE DE CONHECIMENTO COMPLETA DO MONGODB (COLEÇÃO: SCENARIOS) ---\n\n");

            // Serializa cada documento de forma limpa para JSON puro
            String jsonUnificado = documentos.stream()
                    .map(Document::toJson)
                    .collect(Collectors.joining("\n\n"));

            sb.append(jsonUnificado).append("\n\n");

            log.info("[MONGO] {} documentos carregados com sucesso para o contexto científico.", documentos.size());
            return sb.toString();

        } catch (Exception e) {
            log.error("[MONGO] Erro crítico ao extrair os documentos da coleção 'scenarios': {}", e.getMessage(), e);
            return null;
        }
    }
}