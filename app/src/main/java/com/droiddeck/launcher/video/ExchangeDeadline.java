package com.droiddeck.launcher.video;

import java.util.function.LongSupplier;

public final class ExchangeDeadline {
    private final LongSupplier clock;
    private long deadline = Long.MAX_VALUE;
    private boolean expired;

    public ExchangeDeadline() {
        this(System::nanoTime);
    }

    public ExchangeDeadline(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void arm(long absoluteNanos) {
        if (!expired) deadline = absoluteNanos;
    }

    public synchronized boolean expire() {
        if (expired || clock.getAsLong() < deadline) return false;
        expired = true;
        return true;
    }
}
