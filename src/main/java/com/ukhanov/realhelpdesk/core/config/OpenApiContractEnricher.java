package com.ukhanov.realhelpdesk.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Обогащение спеки контрактом из {@code openapi/schema-contract.json}: пример (example) и описание каждого поля каждой
 * схемы.
 *
 * <p>
 * Swagger UI без этого файла показывает голые структуры — фронтендер вынужден спрашивать у бэкендера названия полей и
 * форматы значений. Контракт живёт отдельно от DTO (как и docs/api.md): примеры читаются целиком, правятся в одном
 * месте и закрыты guard-тестом на полноту.
 * </p>
 *
 * <p>
 * {@code ProblemDetail} в контракте не описывается — это стандартный тип Spring, его структура общеизвестна. Упоминание
 * схемы в контракте, отсутствующей в спеке, логируется предупреждением и не валит сборку (смена DTO не должна ломать
 * контракт в рантайме).
 * </p>
 */
@Slf4j
@Component
public class OpenApiContractEnricher implements OpenApiCustomizer {

    private static final String CONTRACT_RESOURCE = "openapi/schema-contract.json";

    /** Запись контракта одной схемы: описание схемы, пример целиком и описания полей. */
    private record ContractEntry(String description, Map<String, Object> example, Map<String, String> fields) {
    }

    private final Map<String, ContractEntry> contract;

    private final ObjectMapper objectMapper;

    public OpenApiContractEnricher(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.contract = loadContract();
    }

    @Override
    public void customise(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
        contract.forEach((name, entry) -> {
            Schema<?> schema = schemas.get(name);
            if (schema == null) {
                logger.warn("Контракт спеки: схема {} не найдена в спеке — пример и описания не применены", name);
                return;
            }
            if (entry.description() != null) {
                schema.setDescription(entry.description());
            }
            if (entry.example() != null) {
                schema.setExample(entry.example());
            }
            applyFieldDescriptions(schema, name, entry.fields());
        });
    }

    private void applyFieldDescriptions(Schema<?> schema, String schemaName, Map<String, String> fields) {
        if (fields == null || schema.getProperties() == null) {
            return;
        }
        fields.forEach((field, description) -> {
            Object property = schema.getProperties().get(field);
            if (property instanceof Schema<?> propertySchema) {
                propertySchema.setDescription(description);
            } else {
                logger.warn("Контракт спеки: поле {}.{} не найдено в схеме — описание не применено", schemaName, field);
            }
        });
    }

    private Map<String, ContractEntry> loadContract() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Не найден ресурс контракта спеки: " + CONTRACT_RESOURCE);
            }
            return parseContract(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать контракт спеки: " + CONTRACT_RESOURCE, e);
        }
    }

    private Map<String, ContractEntry> parseContract(JsonNode root) {
        Map<String, ContractEntry> entries = new HashMap<>();
        for (Map.Entry<String, JsonNode> property : root.properties()) {
            String name = property.getKey();
            if (name.startsWith("_")) {
                continue;
            }
            entries.put(name, parseEntry(property.getValue()));
        }
        return entries;
    }

    private ContractEntry parseEntry(JsonNode node) {
        String description = node.get("description") != null ? node.get("description").stringValue() : null;
        Map<String, Object> example = null;
        JsonNode exampleNode = node.get("example");
        if (exampleNode != null && exampleNode.isObject()) {
            example = objectMapper.convertValue(exampleNode, new TypeReference<Map<String, Object>>() {
            });
        }
        Map<String, String> fields = null;
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && fieldsNode.isObject()) {
            fields = new HashMap<>();
            for (Map.Entry<String, JsonNode> field : fieldsNode.properties()) {
                fields.put(field.getKey(), field.getValue().stringValue());
            }
        }
        return new ContractEntry(description, example, fields);
    }
}
