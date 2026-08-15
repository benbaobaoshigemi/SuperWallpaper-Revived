package com.miui.superwallpapernoaod;

/** Second-order spring used to expose damping and response independently. */
final class DesktopFollowSpring {
    private static final double BASE_FREQUENCY_HZ = 8.0;
    private static final double POSITION_EPSILON = 0.03;
    private static final double VELOCITY_EPSILON = 0.03;
    private static final long MAX_SETTLE_MS = 2000L;

    private boolean initialized;
    private boolean pending;
    private double position;
    private double velocity;
    private double target;
    private long lastUpdateAt;
    private long lastInputAt;

    float onInput(float newTarget, int dampingPercent, int responsePercent, long now) {
        if (!initialized) {
            initialized = true;
            position = newTarget;
            target = newTarget;
            velocity = 0.0;
            lastUpdateAt = now;
            lastInputAt = now;
            return newTarget;
        }
        advance(dampingPercent, responsePercent, now);
        target = newTarget;
        lastInputAt = now;
        pending = !isSettled();
        return (float) position;
    }

    float poll(int dampingPercent, int responsePercent, long now) {
        if (!initialized || !pending) {
            return Float.NaN;
        }
        advance(dampingPercent, responsePercent, now);
        if (isSettled() || now - lastInputAt >= MAX_SETTLE_MS) {
            position = target;
            velocity = 0.0;
            pending = false;
        }
        return (float) position;
    }

    boolean hasPending() {
        return pending;
    }

    void reset() {
        initialized = false;
        pending = false;
        velocity = 0.0;
    }

    private void advance(int dampingPercent, int responsePercent, long now) {
        long elapsedMs = Math.max(0L, Math.min(50L, now - lastUpdateAt));
        lastUpdateAt = now;
        if (elapsedMs == 0L) return;

        double response = Math.max(25, Math.min(400, responsePercent)) / 100.0;
        double damping = Math.max(5, Math.min(400, dampingPercent)) / 100.0;
        double omega = 2.0 * Math.PI * BASE_FREQUENCY_HZ * response;
        double maxStep = Math.min(0.004, 0.25 / (omega * (1.0 + damping)));
        double elapsed = elapsedMs / 1000.0;
        int steps = Math.max(1, (int) Math.ceil(elapsed / maxStep));
        double step = elapsed / steps;
        for (int i = 0; i < steps; i++) {
            double acceleration = omega * omega * (target - position)
                    - 2.0 * damping * omega * velocity;
            velocity += acceleration * step;
            position += velocity * step;
        }
    }

    private boolean isSettled() {
        return Math.abs(target - position) <= POSITION_EPSILON
                && Math.abs(velocity) <= VELOCITY_EPSILON;
    }
}
