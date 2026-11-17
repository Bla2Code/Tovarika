package com.tovarika.tech.shared.application;

/** Milestones estimate completed work, not elapsed or remaining time. */
public final class JobProgress {
    public static final int INPUT_READY = 25;
    public static final int RESULT_READY = 80;
    public static final int READY_TO_COMMIT = 95;

    private JobProgress() {}

    public static Integer visible(String status, int progress) {
        return switch (status) {
            case "queued" -> 0;
            case "processing" -> Math.max(10, Math.min(99, progress));
            case "completed" -> 100;
            default -> null;
        };
    }

    public static void requireIntermediate(int progress) {
        if (progress < 0 || progress >= 100) throw new IllegalArgumentException("Invalid intermediate progress");
    }
}
