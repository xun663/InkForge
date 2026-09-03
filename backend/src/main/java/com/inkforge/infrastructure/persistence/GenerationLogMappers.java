package com.inkforge.infrastructure.persistence;

import com.inkforge.generation.GenerationLog;
import com.inkforge.infrastructure.persistence.entity.GenerationLogEntity;

/** Domain record ↔ JPA entity mapping for generation logs. */
public final class GenerationLogMappers {

    private GenerationLogMappers() {
    }

    public static GenerationLogEntity toEntity(GenerationLog log) {
        GenerationLogEntity entity = new GenerationLogEntity();
        entity.setGenerationId(log.generationId());
        entity.setNovelId(log.novelId());
        entity.setProvider(log.provider());
        entity.setModel(log.model());
        entity.setPromptTokens(log.promptTokens());
        entity.setCompletionTokens(log.completionTokens());
        entity.setLatencyMs(log.latencyMs());
        entity.setEstimatedCostUsd(log.estimatedCostUsd());
        entity.setStatus(log.status());
        entity.setErrorMessage(log.errorMessage());
        entity.setType(log.type());
        entity.setCreatedAt(log.createdAt());
        return entity;
    }

    public static GenerationLog toDomain(GenerationLogEntity entity) {
        return new GenerationLog(
                entity.getGenerationId(), entity.getNovelId(), entity.getProvider(),
                entity.getModel(), entity.getPromptTokens(), entity.getCompletionTokens(),
                entity.getLatencyMs(), entity.getEstimatedCostUsd(), entity.getStatus(),
                entity.getErrorMessage(), entity.getType(), entity.getCreatedAt());
    }
}
