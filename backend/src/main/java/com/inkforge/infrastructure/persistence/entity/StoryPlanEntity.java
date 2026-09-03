package com.inkforge.infrastructure.persistence.entity;

import com.inkforge.planning.EndingAnalysis;
import com.inkforge.planning.PlanStatus;
import com.inkforge.planning.PlanStep;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/** JPA persistence entity for StoryPlan（PostgreSQL profile）。 */
@Entity
@Table(name = "story_plan")
public class StoryPlanEntity {

    @Id
    @Column(name = "plan_id", length = 64)
    private String planId;

    @Column(name = "novel_id", length = 64, nullable = false)
    private String novelId;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "title", length = 512)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "goal", columnDefinition = "text")
    private String goal;

    @Column(name = "expected_arc", columnDefinition = "text")
    private String expectedArc;

    @Column(name = "steps")
    @Convert(converter = PlanningJsonbConverters.PlanStepListConverter.class)
    private List<PlanStep> steps;

    @Column(name = "related_characters")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> relatedCharacters;

    @Column(name = "related_threads")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> relatedThreads;

    @Column(name = "related_events")
    @Convert(converter = JsonbConverters.StringListConverter.class)
    private List<String> relatedEvents;

    @Column(name = "user_instruction", columnDefinition = "text")
    private String userInstruction;

    /** 仅 mode=ENDING 有值。 */
    @Column(name = "analysis")
    @Convert(converter = PlanningJsonbConverters.EndingAnalysisConverter.class)
    private EndingAnalysis analysis;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getNovelId() { return novelId; }
    public void setNovelId(String novelId) { this.novelId = novelId; }
    public com.inkforge.planning.ContinuationMode getMode() {
        return mode == null ? null : com.inkforge.planning.ContinuationMode.valueOf(mode);
    }
    public void setMode(com.inkforge.planning.ContinuationMode mode) {
        this.mode = mode == null ? null : mode.name();
    }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getExpectedArc() { return expectedArc; }
    public void setExpectedArc(String expectedArc) { this.expectedArc = expectedArc; }
    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }
    public List<String> getRelatedCharacters() { return relatedCharacters; }
    public void setRelatedCharacters(List<String> relatedCharacters) { this.relatedCharacters = relatedCharacters; }
    public List<String> getRelatedThreads() { return relatedThreads; }
    public void setRelatedThreads(List<String> relatedThreads) { this.relatedThreads = relatedThreads; }
    public List<String> getRelatedEvents() { return relatedEvents; }
    public void setRelatedEvents(List<String> relatedEvents) { this.relatedEvents = relatedEvents; }
    public String getUserInstruction() { return userInstruction; }
    public void setUserInstruction(String userInstruction) { this.userInstruction = userInstruction; }
    public EndingAnalysis getAnalysis() { return analysis; }
    public void setAnalysis(EndingAnalysis analysis) { this.analysis = analysis; }
    public PlanStatus getStatus() { return status == null ? null : PlanStatus.valueOf(status); }
    public void setStatus(PlanStatus status) { this.status = status == null ? null : status.name(); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
