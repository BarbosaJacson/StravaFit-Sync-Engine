package jackson.stravafit.service;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Slf4j
@Service
public class TrainingStyleService {

    private final MongoTemplate mongoTemplate;
    private final String baseCollectionName;

    public TrainingStyleService(
            MongoTemplate mongoTemplate,
            @Value("${stravafit.mongodb.training-styles-collection:training_styles}") String baseCollectionName) {
        this.mongoTemplate = mongoTemplate;
        this.baseCollectionName = baseCollectionName;
    }

    public String resolveStyleContext(String trainingStyleCode, String gender) {
        if (trainingStyleCode == null || trainingStyleCode.isBlank()) {
            return "Estilo de treino não especificado; manter equilíbrio entre consistência, base aeróbica e segurança.";
        }

        String normalizedGender = (gender != null && !gender.isBlank())
                ? gender.trim().toLowerCase(Locale.ROOT)
                : null;

        try {
            if (normalizedGender != null) {
                String genderCollection = baseCollectionName + "_" + normalizedGender;
                Document docByGenderCol = findStyleDocument(genderCollection, trainingStyleCode, gender);
                if (docByGenderCol != null) {
                    return docByGenderCol.toJson();
                }
            }

            Document docInBaseCol = findStyleDocument(baseCollectionName, trainingStyleCode, gender);
            if (docInBaseCol != null) {
                return docInBaseCol.toJson();
            }
        } catch (Exception e) {
            log.warn("[MONGO-STYLE] Falha ao consultar estilo '{}' (gender={}): {}",
                    trainingStyleCode, gender, e.getMessage());
        }

        return "Estilo de treino: " + trainingStyleCode
                + (gender != null ? " | Gênero: " + gender : "")
                + " (documento ainda não cadastrado no MongoDB).";
    }

    private Document findStyleDocument(String collection, String styleCode, String gender) {
        if (!mongoTemplate.collectionExists(collection)) {
            return null;
        }

        Criteria codeCriteria = new Criteria().orOperator(
                Criteria.where("styleCode").is(styleCode),
                Criteria.where("_id").is(styleCode)
        );

        if (gender != null && !gender.isBlank()) {
            Query withGender = new Query(new Criteria().andOperator(
                    codeCriteria,
                    Criteria.where("gender").is(gender.trim().toUpperCase(Locale.ROOT))
            ));
            Document match = mongoTemplate.findOne(withGender, Document.class, collection);
            if (match != null) {
                return match;
            }
        }

        return mongoTemplate.findOne(new Query(codeCriteria), Document.class, collection);
    }
}