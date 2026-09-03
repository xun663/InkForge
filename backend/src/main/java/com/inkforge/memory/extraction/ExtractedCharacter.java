package com.inkforge.memory.extraction;

import java.util.List;

public record ExtractedCharacter(String name, List<String> aliases, List<ExtractedFact> facts) {

    public ExtractedCharacter {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
