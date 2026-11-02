package com.mazzei.cleanframe;

/** Android-independent Home launch/lifecycle rules, exercised on the JVM in CI. */
final class HomeLaunchPolicy {
    static final int MAX_FAILURES = 3;
    static final long LAUNCH_GRACE_MS = 3000, DUPLICATE_MS = 500, USABLE_MS = 1500;

    static int failuresForAttempt(long now, long lastLaunch, long pending, int failures) {
        if (now - lastLaunch < DUPLICATE_MS) return -1;
        if (pending > now) pending = -1;
        if (pending >= 0 && now - pending < LAUNCH_GRACE_MS) return -1;
        return failures + (pending >= 0 ? 1 : 0);
    }

    static boolean launchOnBind(boolean optedIn, long uptime) {
        return optedIn && uptime < 60000;
    }

    static final class Session {
        private int generation;
        private boolean visible, acknowledged;
        private long readySince = -1;
        void resume() { generation++; visible = true; acknowledged = false; readySince = -1; }
        void pause() { generation++; visible = false; readySince = -1; }
        int generation() { return generation; }
        boolean shouldPoll(int token) { return token == generation && visible && !acknowledged; }
        boolean observeReady(int token, boolean ready, long now) {
            if (!shouldPoll(token)) return false;
            if (!ready) { readySince = -1; return false; }
            if (readySince < 0) readySince = now;
            if (now - readySince < USABLE_MS) return false;
            acknowledged = true;
            return true;
        }
    }
}
