package com.inkforge.context;

import com.inkforge.novel.Novel;
import com.inkforge.provider.ChatMessage;

import java.util.List;

/**
 * Builds the prompt context for a continuation request.
 * Phase 1 implementation: recent-chapters sliding window under a token budget.
 * The budget is a PARAMETER — the yml value (8192) is only a default.
 * Phase 4 evolves this into the full ContextBudgetManager.
 */
public interface ContinuationContextBuilder {

    /**
     * @param novel             the novel to continue
     * @param contextMaxTokens  total budget for system prompt + user context
     * @return rendered prompt messages (system + user), guaranteed to stay within budget
     */
    List<ChatMessage> build(Novel novel, int contextMaxTokens);

    /**
     * P3-E: build with retrieval observability. Default implementation has no
     * retrieval (trace fields null/0) — RecentChaptersContextBuilder stays untouched.
     */
    default ContextBuildResult buildWithTrace(Novel novel, int contextMaxTokens, String generationId) {
        return new ContextBuildResult(build(novel, contextMaxTokens), null, 0);
    }
}
