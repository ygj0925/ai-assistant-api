/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.assistant.reminder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * Resolves bounded calendar reminders in their stored wall-clock zone.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
final class ReminderCalendar {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter
        .ofPattern("uuuu-MM-dd'T'HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
    private static final Duration LATE_LIMIT = Duration.ofHours(24);
    private static final int SEARCH_SLOTS = 5;

    private ReminderCalendar() {
    }

    static Plan plan(String local, String region, String repeat, String quietStart,
        String quietEnd) {
        if (local == null || local.length() != 16) {
            throw new IllegalArgumentException("Choose a valid local time and IANA time zone.");
        }
        LocalDateTime start = LocalDateTime.parse(local, MINUTE);
        if (start.getYear() < 1900) {
            throw new IllegalArgumentException("The reminder date is out of range.");
        }
        QuietWindow window = quietWindow(region, quietStart, quietEnd);
        Cadence cadence = Cadence.parse(repeat);
        return new Plan(start, window.zone(), cadence, window.quiet());
    }

    static QuietWindow quietWindow(String region, String quietStart, String quietEnd) {
        if (region == null || region.length() > 64
            || !ZoneId.getAvailableZoneIds().contains(region)) {
            throw new IllegalArgumentException("Choose a valid IANA time zone.");
        }
        Quiet quiet = quietStart == null && quietEnd == null ? null
            : Quiet.parse(quietStart, quietEnd);
        return new QuietWindow(ZoneId.of(region), quiet);
    }

    static boolean expired(Instant delivery, Instant now) {
        return now.isAfter(delivery.plus(LATE_LIMIT));
    }

    static Slot firstFuture(Plan plan, Instant now) {
        long anchor = plan.anchorDay();
        if (plan.cadence() == Cadence.ONCE) {
            Slot once = slot(plan, anchor);
            return once.plannedAt().isAfter(now) ? once : null;
        }
        long step = plan.cadence().days;
        long lower = Math.max(anchor, localDay(plan, now) - 2);
        long first = anchor + (lower - anchor + step - 1) / step * step;
        for (int attempt = 0; attempt < SEARCH_SLOTS; attempt++) {
            Slot candidate = slot(plan, first + attempt * step);
            if (candidate.plannedAt().isAfter(now)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No future reminder slot was found.");
    }

    static Decision due(Plan plan, long pendingDay, Instant now) {
        Slot pending = slot(plan, pendingDay);
        if (pending.deliveryAt().isAfter(now)) {
            return Decision.waitFor(pending, pending.deliveryAt());
        }
        Instant quietEnd = afterQuiet(plan.zone(), plan.quiet(), now);
        if (quietEnd.isAfter(now)) {
            return Decision.waitFor(pending, quietEnd);
        }
        Slot latest = plan.cadence() == Cadence.ONCE ? pending : latestDue(plan, pending, now);
        boolean expired = expired(latest.deliveryAt(), now);
        if (plan.cadence() == Cadence.ONCE) {
            return new Decision(latest, expired, null, null);
        }

        Slot next = slot(plan, latest.day() + plan.cadence().days);
        return new Decision(expired ? null : latest, false, next, next.deliveryAt());
    }

    static Slot slot(Plan plan, long day) {
        LocalDateTime local = LocalDate.ofEpochDay(day).atTime(plan.start().toLocalTime());
        Instant planned = scheduledInstant(local, plan.zone());
        return new Slot(day, local, planned, afterQuiet(plan.zone(), plan.quiet(), planned));
    }

    static String format(LocalDateTime time) {
        return MINUTE.format(time);
    }

    private static Slot latestDue(Plan plan, Slot pending, Instant now) {
        long step = plan.cadence().days;
        long anchor = plan.anchorDay();
        long offset = Math.max(0, localDay(plan, now) - anchor);
        long last = anchor + offset / step * step;
        for (int count = 0; count < SEARCH_SLOTS && last >= pending.day(); count++, last -= step) {
            Slot candidate = slot(plan, last);
            if (!candidate.deliveryAt().isAfter(now)) {
                return candidate;
            }
        }
        return pending;
    }

    private static long localDay(Plan plan, Instant instant) {
        return instant.atZone(plan.zone()).toLocalDate().toEpochDay();
    }

    private static Instant scheduledInstant(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (!offsets.isEmpty()) {
            return local.toInstant(offsets.get(0));
        }
        var gap = zone.getRules().getTransition(local);
        return local.plus(gap.getDuration()).toInstant(gap.getOffsetAfter());
    }

    private static Instant afterQuiet(ZoneId zone, Quiet quiet, Instant candidate) {
        if (quiet == null) {
            return candidate;
        }
        LocalDate date = candidate.atZone(zone).toLocalDate();
        Instant today = quietEnd(zone, quiet, candidate, date);
        Instant yesterday = quietEnd(zone, quiet, candidate, date.minusDays(1));
        return today.isAfter(yesterday) ? today : yesterday;
    }

    private static Instant quietEnd(ZoneId zone, Quiet quiet, Instant candidate, LocalDate date) {
        LocalDate endDay = quiet.start().isBefore(quiet.end()) ? date : date.plusDays(1);
        Instant start = boundary(date.atTime(quiet.start()), zone, false);
        Instant end = boundary(endDay.atTime(quiet.end()), zone, true);
        return !candidate.isBefore(start) && candidate.isBefore(end) ? end : candidate;
    }

    private static Instant boundary(LocalDateTime local, ZoneId zone, boolean end) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return zone.getRules().getTransition(local).getInstant();
        }
        return local.toInstant(offsets.get(end ? offsets.size() - 1 : 0));
    }

    enum Cadence {

        ONCE("once", 0),
        DAILY("daily", 1),
        WEEKLY("weekly", 7);

        final String value;
        final int days;

        Cadence(String value, int days) {
            this.value = value;
            this.days = days;
        }

        private static Cadence parse(String value) {
            for (Cadence cadence : values()) {
                if (cadence.value.equals(value)) {
                    return cadence;
                }
            }
            throw new IllegalArgumentException("Choose a supported reminder cadence.");
        }
    }

    record Quiet(LocalTime start, LocalTime end) {

        private static Quiet parse(String start, String end) {
            if (start == null || start.length() != 5 || end == null || end.length() != 5) {
                throw new IllegalArgumentException("Specify both quiet-hour boundaries.");
            }
            LocalTime from = LocalTime.parse(start, TIME);
            LocalTime to = LocalTime.parse(end, TIME);
            if (from.equals(to)) {
                throw new IllegalArgumentException("Quiet-hour boundaries must differ.");
            }
            return new Quiet(from, to);
        }
    }

    record Plan(LocalDateTime start, ZoneId zone, Cadence cadence, Quiet quiet) {

        long anchorDay() {
            return start.toLocalDate().toEpochDay();
        }
    }

    record QuietWindow(ZoneId zone, Quiet quiet) {

        Instant release(Instant candidate) {
            return afterQuiet(zone, quiet, candidate);
        }
    }

    record Slot(long day, LocalDateTime local, Instant plannedAt, Instant deliveryAt) {
    }

    record Decision(Slot occurrence, boolean expired, Slot next, Instant wakeAt) {

        private static Decision waitFor(Slot pending, Instant wake) {
            return new Decision(null, false, pending, wake);
        }
    }
}
