package jackson.stravafit.service;

import jackson.stravafit.model.Gender;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KnowledgeService {

    private final MongoTemplate mongoTemplate;
    private final Map<Gender, String> contextCache = new ConcurrentHashMap<>();

    // O Spring Boot injeta o MongoTemplate pré-configurado com a sua conexão do strava_fit_db
    public KnowledgeService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String getScientificContext(Gender gender) {
        try {
            // 1. Definição do gênero ativo com fallback seguro para MALE
            Gender activeGender = (gender != null) ? gender : Gender.MALE;

            // 2. CACHE FIRST: Se já existir na memória, retorna imediatamente sem ir ao Mongo
            if (contextCache.containsKey(activeGender)) {
                log.info("[CACHE] Retornando contexto científico em memória para o gênero: {}", activeGender);
                return contextCache.get(activeGender);
            }

            // 3. Define dinamicamente o nome da coleção usando a variável activeGender
            String collectionName = (activeGender == Gender.FEMALE) ? "scenarios_female" : "scenarios_male";
            log.info("[MONGO] Buscando documentos de inteligência na coleção '{}'...", collectionName);

            List<Document> documentos = mongoTemplate.findAll(Document.class, collectionName);

            if (documentos.isEmpty()) {
                log.warn("[MONGO] Nenhum documento foi encontrado na coleção '{}'.", collectionName);
                return null;
            }

            // 4. Monta a base unificada de JSONs
            StringBuilder sb = new StringBuilder();
            sb.append("--- BASE DE CONHECIMENTO COMPLETA DO MONGODB (COLEÇÃO: ")
                    .append(collectionName.toUpperCase())
                    .append(") ---\n\n");

            String jsonUnificado = documentos.stream()
                    .map(Document::toJson)
                    .collect(Collectors.joining("\n\n"));

            sb.append(jsonUnificado).append("\n\n");
            String finalContext = sb.toString();

            // 5. Salva no Cache para as próximas chamadas
            contextCache.put(activeGender, finalContext);
            log.info("[CACHE] Contexto para '{}' salvo no cache com sucesso ({} documentos).", activeGender, documentos.size());

            return finalContext;

        } catch (Exception e) {
            log.error("[MONGO] Erro crítico ao extrair os documentos do MongoDB para o gênero {}: {}", gender, e.getMessage(), e);
            return null;
        }
    }

    public void clearCache() {
        log.info("[CACHE] Limpando o cache de conhecimento científico em memória.");
        contextCache.clear();
    }
}