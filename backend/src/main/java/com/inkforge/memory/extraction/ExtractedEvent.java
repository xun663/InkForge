package com.inkforge.memory.extraction;

import java.util.List;

public record ExtractedEvent(
        String title,
        String description,
        List<String> participants,
        String location,
        List<String> consequences,
        int importance,
        String sourceQuote) {

    public ExtractedEvent {
        participants = participants == null ? List.of() : List.copyOf(participants);
        consequences = consequences == null ? List.of() : List.copyOf(consequences);
    }
}
