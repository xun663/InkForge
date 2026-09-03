package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.planning.EndingAnalysis;
import com.inkforge.planning.PlanStep;
import jakarta.persistence.AttributeConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * P6 规划层 JSONB converters（Jackson 3，风格对齐 {@link JsonbConverters}）。
 * 独立成文件，避免触碰已有未提交变更的 JsonbConverters。
 */
public final class PlanningJsonbConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanningJsonbConverters() {
    }

    public static class PlanStepListConverter implements AttributeConverter<List<PlanStep>, String> {

        @Override
        public String convertToDatabaseColumn(List<PlanStep> attribute) {
            return write(attribute);
        }

        @Override
        public List<PlanStep> convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<List<PlanStep>>() {
            });
        }
    }

    public static class EndingAnalysisConverter implements AttributeConverter<EndingAnalysis, String> {

        @Override
        public String convertToDatabaseColumn(EndingAnalysis attribute) {
            return write(attribute);
        }

        @Override
        public EndingAnalysis convertToEntityAttribute(String dbData) {
            return read(dbData, new TypeReference<EndingAnalysis>() {
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
            throw new IllegalStateException("JSONB 序列化失败", e);
        }
    }

    private static <T> T read(String data, TypeReference<T> type) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(data, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 反序列化失败", e);
        }
    }
}
