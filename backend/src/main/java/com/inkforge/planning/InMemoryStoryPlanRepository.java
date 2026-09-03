package com.inkforge.planning;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** InMemory StoryPlan 仓储。重启即失（开发/Demo 模式，与其它 InMemory 仓储一致）。 */
@Repository
public class InMemoryStoryPlanRepository implements StoryPlanRepository {

    private final Map<String, StoryPlan> byId = new ConcurrentHashMap<>();

    @Override
    public StoryPlan save(StoryPlan plan) {
        byId.put(plan.planId(), plan);
        return plan;
    }

    @Override
    public Optional<StoryPlan> findById(String planId) {
        return Optional.ofNullable(byId.get(planId));
    }

    @Override
    public List<StoryPlan> findByNovelId(String novelId) {
        return byId.values().stream()
                .filter(p -> p.novelId().equals(novelId))
                .sorted(Comparator.comparing(StoryPlan::createdAt).reversed())
                .toList();
    }
}
