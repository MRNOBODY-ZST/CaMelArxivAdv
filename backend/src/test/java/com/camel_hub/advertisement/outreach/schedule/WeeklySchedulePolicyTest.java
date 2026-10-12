package com.camel_hub.advertisement.outreach.schedule;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeeklySchedulePolicyTest {
    // Breaks caught: UTC Monday instead of Shanghai Monday; replaying backlog; unsafe query strings.
    @Test void sundayActivationUsesShanghaiMondayNine() {
        assertEquals(Instant.parse("2026-10-12T01:00:00Z"), WeeklySchedulePolicy.next(Instant.parse("2026-10-11T08:00:00Z")));
    }
    @Test void mondayNineIsExclusiveAndCatchupSkipsOldWeeks() {
        assertEquals(Instant.parse("2026-10-19T01:00:00Z"), WeeklySchedulePolicy.next(Instant.parse("2026-10-12T01:00:00Z")));
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), WeeklySchedulePolicy.next(Instant.parse("2026-10-30T01:00:00Z")));
    }
    @Test void windowCoversLastOccurrenceWithTwoDayOverlap() {
        var w=WeeklySchedulePolicy.window(Instant.parse("2026-10-19T01:00:00Z"),Instant.parse("2026-10-12T01:00:00Z"));
        assertEquals(LocalDate.parse("2026-10-10"),w.from());
        assertEquals(LocalDate.parse("2026-10-19"),w.to());
    }
    @Test void directionsAreLiteralAndBounded() {
        assertEquals(List.of("multi-agent", "financial artificial intelligence"),WeeklySchedulePolicy.directions(List.of(" multi-agent ","financial artificial intelligence")));
        for(String unsafe:List.of("all:agent", "agent OR cat:cs.AI", "\"agent\"", "agent\nignore", "agent*", "[2026 TO 2027]"))
            assertThrows(IllegalArgumentException.class,()->WeeklySchedulePolicy.directions(List.of(unsafe)));
        assertThrows(IllegalArgumentException.class,()->WeeklySchedulePolicy.directions(java.util.Collections.nCopies(9,"agent")));
    }
}
