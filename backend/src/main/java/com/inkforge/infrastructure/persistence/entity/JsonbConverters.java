package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.memory.MemoryExtractionStats;
import com.inkforge.memory.SummaryCharacter;
import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JSONB converters backed by tools.jackson (Jackson 3).
 * Deliberately chosen over Hibernate's @JdbcTypeCode(SqlTypes.JSON) to avoid the
 * Hibernate 7 + Jackson 3 integration uncertainty — the mapping stays fully explicit
 * and deterministic. The columns are jsonb; PostgreSQL's assignment cast accepts the
 * string representation.
 */
public final class JsonbConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonbConverters() {
    }

    public static class StringListConverter implements AttributeConverter<List<String>, String> {

        @Override
        public String convertToDatabaseColumn(List<String> attribute) {
            return write(attribute);
        }

        @Override
        public List<String> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<List<String>>() {
            });
        }
    }

    public static class IntegerListConverter implements AttributeConverter<List<Integer>, String> {

        @Override
        public String convertToDatabaseColumn(List<Integer> attribute) {
            return write(attribute);
        }

        @Override
        public List<Integer> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<List<Integer>>() {
            });
        }
    }

    public static class SummaryCharacterListConverter
            implements AttributeConverter<List<SummaryCharacter>, String> {

        @Override
        public String convertToDatabaseColumn(List<SummaryCharacter> attribute) {
            return write(attribute);
        }

        @Override
        public List<SummaryCharacter> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<List<SummaryCharacter>>() {
            });
        }
    }

    public static class StatsConverter implements AttributeConverter<MemoryExtractionStats, String> {

        @Override
        public String convertToDatabaseColumn(MemoryExtractionStats attribute) {
            return write(attribute);
        }

        @Override
        public MemoryExtractionStats convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<MemoryExtractionStats>() {
            });
        }
    }

    public static class RetrievalPipelineConverter
            implements AttributeConverter<Map<String, List<com.inkforge.retrieval.RetrievalResult>>, String> {

        @Override
        public String convertToDatabaseColumn(Map<String, List<com.inkforge.retrieval.RetrievalResult>> attribute) {
            return write(attribute);
        }

        @Override
        public Map<String, List<com.inkforge.retrieval.RetrievalResult>> convertToEntityAttribute(String dbData) {
            return read(dbData,
                    new TypeReference<Map<String, List<com.inkforge.retrieval.RetrievalResult>>>() {
                    });
        }
    }

    private static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 序列化失败: " + value.getClass(), e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 反序列化失败: " + json, e);
        }
    }
}
