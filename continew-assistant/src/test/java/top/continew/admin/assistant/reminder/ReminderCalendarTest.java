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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Tests calendar behavior with a fixed clock and the target zone rules.
 *
 * @author yuanguangji
 * @since 4.2.0
 */
class ReminderCalendarTest {

    @Test
    void defersEventReceiptsAcrossSpringGapAndBothAutumnFoldOffsets() {
        var spring = ReminderCalendar.quietWindow("America/New_York", "01:30", "02:30");
        assertThat(spring.release(now("2026-03-08T06:45:00Z")))
            .isEqualTo(now("2026-03-08T07:00:00Z"));
        var fall = ReminderCalendar.quietWindow("America/New_York", "00:30", "01:30");
        assertThat(fall.release(now("2026-11-01T05:15:00Z")))
            .isEqualTo(now("2026-11-01T06:30:00Z"));
        assertThat(fall.release(now("2026-11-01T06:15:00Z")))
            .isEqualTo(now("2026-11-01T06:30:00Z"));
        assertThat(ReminderCalendar.expired(now("2026-11-01T06:30:00Z"),
            now("2026-11-02T06:30:00Z"))).isFalse();
        assertThat(ReminderCalendar.expired(now("2026-11-01T06:30:00Z"),
            now("2026-11-02T06:30:01Z"))).isTrue();
    }

    @Test
    void shiftsSpringGapByTheTransitionLengthWithoutDriftingTomorrow() {
        var plan =
            ReminderCalendar.plan("2026-03-08T02:30", "America/New_York", "daily", null, null);
        var first = ReminderCalendar.firstFuture(plan, now("2026-03-08T06:00:00Z"));
        assertThat(first.plannedAt()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        var decision = ReminderCalendar.due(plan, first.day(), first.plannedAt());
        assertThat(decision.occurrence()).isEqualTo(first);
        assertThat(decision.next().plannedAt()).isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));

        var halfHour =
            ReminderCalendar.plan("2026-10-04T02:15", "Australia/Lord_Howe", "once", null, null);
        assertThat(ReminderCalendar.slot(halfHour, halfHour.anchorDay()).plannedAt())
            .isEqualTo(Instant.parse("2026-10-03T15:45:00Z"));
    }

    @Test
    void usesTheFirstFoldInstantForOnlyOneLogicalSlot() {
        var plan =
            ReminderCalendar.plan("2026-11-01T01:30", "America/New_York", "daily", null, null);
        var slot = ReminderCalendar.firstFuture(plan, now("2026-11-01T04:30:00Z"));
        assertThat(slot.plannedAt()).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        var next = ReminderCalendar.firstFuture(plan, now("2026-11-01T06:15:00Z"));
        assertThat(next.day()).isEqualTo(slot.day() + 1);
        assertThat(next.plannedAt()).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void defersOvernightAndKeepsFoldQuietUntilItsLaterBoundary() {
        var crossing = ReminderCalendar.plan("2026-01-01T23:00", "UTC", "once", "22:00", "07:00");
        var evening = ReminderCalendar.slot(crossing, crossing.anchorDay());
        assertThat(evening.deliveryAt()).isEqualTo(Instant.parse("2026-01-02T07:00:00Z"));
        assertThat(ReminderCalendar.due(crossing, evening.day(), now("2026-01-02T06:59:59Z"))
            .occurrence()).isNull();
        assertThat(ReminderCalendar.due(crossing, evening.day(), evening.deliveryAt()).occurrence())
            .isEqualTo(evening);

        var fold =
            ReminderCalendar.plan("2026-11-01T00:45", "America/New_York", "once", "00:30", "01:30");
        assertThat(ReminderCalendar.slot(fold, fold.anchorDay()).deliveryAt())
            .isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
        var gap =
            ReminderCalendar.plan("2026-03-08T00:45", "America/New_York", "once", "22:00", "02:15");
        assertThat(ReminderCalendar.slot(gap, gap.anchorDay()).deliveryAt())
            .isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
    }

    @Test
    void recoveryDuringQuietDefersEvenAnOriginallyAudibleSlot() {
        var plan = ReminderCalendar.plan("2026-01-01T20:00", "UTC", "once", "22:00", "07:00");
        var decision = ReminderCalendar.due(plan, plan.anchorDay(), now("2026-01-01T23:00:00Z"));
        assertThat(decision.occurrence()).isNull();
        assertThat(decision.next().day()).isEqualTo(plan.anchorDay());
        assertThat(decision.wakeAt()).isEqualTo(Instant.parse("2026-01-02T07:00:00Z"));
    }

    @Test
    void boundsBackfillToTheLatestEligibleSlotInTwentyFourHours() {
        var weekly = ReminderCalendar.plan("2025-01-06T09:00", "UTC", "weekly", null, null);
        var recovered =
            ReminderCalendar.due(weekly, weekly.anchorDay(), now("2026-10-05T09:30:00Z"));
        assertThat(recovered.occurrence().plannedAt())
            .isEqualTo(Instant.parse("2026-10-05T09:00:00Z"));
        assertThat(recovered.next().plannedAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));

        var expiredWeek =
            ReminderCalendar.due(weekly, weekly.anchorDay(), now("2026-10-06T09:00:01Z"));
        assertThat(expiredWeek.occurrence()).isNull();
        assertThat(expiredWeek.next()).isEqualTo(recovered.next());

        var once = ReminderCalendar.plan("2026-11-01T12:00", "UTC", "once", null, null);
        assertThat(
            ReminderCalendar.due(once, once.anchorDay(), now("2026-11-02T12:00:00Z")).expired())
            .isFalse();
        assertThat(
            ReminderCalendar.due(once, once.anchorDay(), now("2026-11-02T12:00:01Z")).expired())
            .isTrue();
        assertThat(ReminderCalendar.firstFuture(once, now("2026-11-01T12:00:00Z"))).isNull();
    }

    @Test
    void rejectsInvalidWallTimeZoneAndAmbiguousQuietConfiguration() {
        assertThatThrownBy(
            () -> ReminderCalendar.plan("2026-02-30T12:00", "UTC", "once", null, null))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
            () -> ReminderCalendar.plan("2026-02-28T12:00", "+08:00", "once", null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
            () -> ReminderCalendar.plan("2026-02-28T12:00", "UTC", "once", "07:00", "07:00"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Instant now(String value) {
        return Clock.fixed(Instant.parse(value), ZoneOffset.UTC).instant();
    }
}
