package com.inkforge.retrieval;

import com.inkforge.novel.Novel;

/**
 * Retrieval port used by the context builder. Implementations must NEVER propagate
 * failures: any exception → empty RetrievedMemory (continuation keeps working on the
 * P2 memory path).
 */
public interface RetrievedMemoryProvider {

    RetrievedMemory retrieve(Novel novel, int contextMaxTokens, String generationId);
}
