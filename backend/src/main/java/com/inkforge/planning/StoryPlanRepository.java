package com.inkforge.planning;

import java.util.List;
import java.util.Optional;

/** StoryPlan 仓储端口（域层）。default profile 用 InMemory，postgres profile 用 JPA。 */
public interface StoryPlanRepository {

    StoryPlan save(StoryPlan plan);

    Optional<StoryPlan> findById(String planId);

    List<StoryPlan> findByNovelId(String novelId);
}
