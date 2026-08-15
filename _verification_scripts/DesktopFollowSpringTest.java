package com.miui.superwallpapernoaod;

public final class DesktopFollowSpringTest {
    public static void main(String[] args) {
        assertSettles(100, 100);
        assertSettles(25, 100);
        assertSettles(400, 400);
        assertDampingChangesOvershoot();
        assertResponseChangesProgress();
        System.out.println("DesktopFollowSpringTest PASS");
    }

    private static void assertSettles(int damping, int response) {
        DesktopFollowSpring spring = new DesktopFollowSpring();
        spring.onInput(0f, damping, response, 0L);
        spring.onInput(100f, damping, response, 16L);
        float value = Float.NaN;
        for (long now = 24L; now <= 2200L && spring.hasPending(); now += 8L) {
            value = spring.poll(damping, response, now);
        }
        assertNear(100f, value, "settle " + damping + "/" + response);
    }

    private static void assertDampingChangesOvershoot() {
        float loose = maximumAfterStep(25, 100);
        float stable = maximumAfterStep(200, 100);
        if (!(loose > stable && loose > 100f)) {
            throw new AssertionError("damping did not reduce rebound: " + loose + " / " + stable);
        }
    }

    private static void assertResponseChangesProgress() {
        float relaxed = valueAfter(100, 50, 80L);
        float direct = valueAfter(100, 200, 80L);
        if (!(direct > relaxed)) {
            throw new AssertionError("response did not increase tracking: " + relaxed + " / " + direct);
        }
    }

    private static float maximumAfterStep(int damping, int response) {
        DesktopFollowSpring spring = new DesktopFollowSpring();
        spring.onInput(0f, damping, response, 0L);
        spring.onInput(100f, damping, response, 16L);
        float maximum = 0f;
        for (long now = 24L; now <= 600L; now += 8L) {
            float value = spring.poll(damping, response, now);
            if (!Float.isNaN(value)) maximum = Math.max(maximum, value);
        }
        return maximum;
    }

    private static float valueAfter(int damping, int response, long duration) {
        DesktopFollowSpring spring = new DesktopFollowSpring();
        spring.onInput(0f, damping, response, 0L);
        spring.onInput(100f, damping, response, 16L);
        float value = 0f;
        for (long now = 24L; now <= duration; now += 8L) {
            float current = spring.poll(damping, response, now);
            if (!Float.isNaN(current)) value = current;
        }
        return value;
    }

    private static void assertNear(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 0.001f) {
            throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
        }
    }
}
