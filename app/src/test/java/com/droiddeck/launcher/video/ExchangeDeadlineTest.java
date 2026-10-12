package com.droiddeck.launcher.video;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class ExchangeDeadlineTest {
    @Test public void partialProgressCannotExtendAbsoluteDeadline() {
        AtomicLong clock = new AtomicLong();
        ExchangeDeadline deadline = new ExchangeDeadline(clock::get);
        deadline.arm(1_000_000_000L);
        for (int i = 1; i < 10; i++) {
            clock.set(i * 100_000_000L);
            deadline.arm(1_000_000_000L);
            assertFalse(deadline.expire());
        }
        clock.set(1_000_000_000L);
        assertTrue(deadline.expire());
        assertFalse(deadline.expire());
        deadline.arm(3_000_000_000L);
        assertFalse(deadline.expire());
    }

    @Test public void laterExchangeCanArmNewDeadlineBeforeExpiry() {
        AtomicLong clock = new AtomicLong();
        ExchangeDeadline deadline = new ExchangeDeadline(clock::get);
        deadline.arm(1_000_000_000L);
        clock.set(500_000_000L);
        deadline.arm(1_500_000_000L);
        clock.set(1_000_000_000L);
        assertFalse(deadline.expire());
        clock.set(1_500_000_000L);
        assertTrue(deadline.expire());
    }
}
