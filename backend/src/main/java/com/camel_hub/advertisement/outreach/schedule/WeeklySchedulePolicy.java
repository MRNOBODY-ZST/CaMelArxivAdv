package com.camel_hub.advertisement.outreach.schedule;
import java.time.*;
import java.util.List;
final class WeeklySchedulePolicy {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    record Window(LocalDate from,LocalDate to) {}
    static Instant next(Instant now) {
        var local=now.atZone(ZONE);
        var monday=local.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(9,0).atZone(ZONE);
        return (monday.toInstant().isAfter(now)?monday:monday.plusWeeks(1)).toInstant();
    }
    static Window window(Instant now,Instant previous) {
        return new Window((previous==null?now.minus(Duration.ofDays(7)):previous).atZone(ZONE).toLocalDate().minusDays(2),now.atZone(ZONE).toLocalDate());
    }
    static List<String> directions(List<String> values) {
        if(values==null||values.isEmpty()||values.size()>8) throw new IllegalArgumentException("Directions must contain 1–8 literal research phrases");
        var result=values.stream().map(v->{
            if(v==null||!v.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} .+/-]{0,119}")||v.contains(" OR ")||v.contains(" AND ")||v.contains(" NOT ")) {
                // Strip edge whitespace only; control characters are always rejected.
                if(v==null||v.chars().anyMatch(Character::isISOControl)||!v.strip().matches("[\\p{L}\\p{N}][\\p{L}\\p{N} .+/-]{0,119}")||v.contains(" OR ")||v.contains(" AND ")||v.contains(" NOT "))
                    throw new IllegalArgumentException("Directions must be bounded literal research phrases");
            }
            return v.strip();
        }).distinct().toList();
        return List.copyOf(result);
    }
}
