package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.PlotThreadEntity;
import com.inkforge.infrastructure.persistence.entity.StoryPlanEntity;
import com.inkforge.planning.PlotThread;
import com.inkforge.planning.StoryPlan;

/** Domain record ↔ JPA entity mapping for planning (StoryPlan / PlotThread). */
public final class PlanningMappers {

    private PlanningMappers() {
    }

    public static StoryPlanEntity toEntity(StoryPlan plan) {
        StoryPlanEntity e = new StoryPlanEntity();
        e.setPlanId(plan.planId());
        e.setNovelId(plan.novelId());
        e.setMode(plan.mode());
        e.setTitle(plan.title());
        e.setSummary(plan.summary());
        e.setGoal(plan.goal());
        e.setExpectedArc(plan.expectedArc());
        e.setSteps(plan.steps());
        e.setRelatedCharacters(plan.relatedCharacters());
        e.setRelatedThreads(plan.relatedThreads());
        e.setRelatedEvents(plan.relatedEvents());
        e.setUserInstruction(plan.userInstruction());
        e.setAnalysis(plan.analysis());
        e.setStatus(plan.status());
        e.setCreatedAt(plan.createdAt());
        e.setUpdatedAt(plan.updatedAt());
        return e;
    }

    public static StoryPlan toDomain(StoryPlanEntity e) {
        return new StoryPlan(
                e.getPlanId(), e.getNovelId(), e.getMode(), e.getTitle(), e.getSummary(),
                e.getGoal(), e.getExpectedArc(), e.getSteps(), e.getRelatedCharacters(),
                e.getRelatedThreads(), e.getRelatedEvents(), e.getUserInstruction(),
                e.getAnalysis(), e.getStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static PlotThreadEntity toEntity(PlotThread thread) {
        PlotThreadEntity e = new PlotThreadEntity();
        e.setId(thread.id());
        e.setNovelId(thread.novelId());
        e.setTitle(thread.title());
        e.setTitleNormalized(PlotThread.normalized(thread.title()));
        e.setSummary(thread.summary());
        e.setStatus(thread.status());
        e.setFirstSeenChapter(thread.firstSeenChapter());
        e.setLastSeenChapter(thread.lastSeenChapter());
        e.setRelatedCharacters(thread.relatedCharacters());
        e.setCreatedAt(thread.createdAt());
        e.setUpdatedAt(thread.updatedAt());
        return e;
    }

    public static PlotThread toDomain(PlotThreadEntity e) {
        return new PlotThread(
                e.getId(), e.getNovelId(), e.getTitle(), e.getSummary(), e.getStatus(),
                e.getFirstSeenChapter(), e.getLastSeenChapter(),
                e.getRelatedCharacters() == null ? java.util.List.of() : e.getRelatedCharacters(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
