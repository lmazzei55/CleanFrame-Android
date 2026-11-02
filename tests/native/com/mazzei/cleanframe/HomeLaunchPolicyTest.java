package com.mazzei.cleanframe;

/** Runs production policy code on the JVM without Android mocks or a TV. */
public final class HomeLaunchPolicyTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        long pending = -1, last = -500, now = 10000;
        int failures = 0, launches = 0;
        HomeLaunchPolicy.Session session = new HomeLaunchPolicy.Session();
        // OEM/system overlays repeatedly cover the Activity before its UI stays usable.
        for (int attempt = 0; attempt < 12; attempt++) {
            int next = HomeLaunchPolicy.failuresForAttempt(now, last, pending, failures);
            if (next >= HomeLaunchPolicy.MAX_FAILURES) { failures = next; pending = -1; }
            else if (next >= 0) {
                failures = next; pending = now; last = now; launches++;
                session.resume(); int token = session.generation();
                check(!session.observeReady(token, true, now + 100), "first ready observation is not success");
                session.pause();
                check(!session.observeReady(token, true, now + 2000), "late callback after pause cannot count success");
            }
            now += 4000;
        }
        check(launches == 3, "rapid pause/bounce stops after three unsuccessful redirects");
        check(failures == 3, "stopped state remains latched without a pending launch");
        check(HomeLaunchPolicy.failuresForAttempt(1000, 900, -1, 0) == -1, "duplicate events ignored");
        check(HomeLaunchPolicy.failuresForAttempt(1200, 0, 1000, 0) == -1, "slow launch gets grace period");
        check(HomeLaunchPolicy.failuresForAttempt(5000, -500, 9000, 0) == 0, "pre-reboot timestamp is discarded");

        session.resume(); int old = session.generation();
        check(!session.observeReady(old, false, 0), "blank/unready UI cannot reset failures");
        check(!session.observeReady(old, true, 100), "ready period starts after actual UI readiness");
        check(!session.observeReady(old, true, 1599), "requires full usable interval");
        check(session.observeReady(old, true, 1600), "stable usable UI counts as success");
        check(!session.shouldPoll(old), "success is acknowledged only once");
        session.pause(); session.resume(); int current = session.generation();
        check(!session.observeReady(old, true, 5000), "callback from earlier resume is rejected");
        check(!session.observeReady(current, true, 5000), "new resume starts a new ready period");
        check(!session.observeReady(current, false, 6000), "lost readiness resets the period");
        check(!session.observeReady(current, true, 7000), "recovered UI needs a new stable interval");
        check(session.observeReady(current, true, 8500), "recovered usable UI can re-arm Home");

        check(!HomeLaunchPolicy.launchOnBind(false, 20000), "default startup preserves HDMI/Live TV");
        check(HomeLaunchPolicy.launchOnBind(true, 20000), "explicit startup opt-in works");
        check(!HomeLaunchPolicy.launchOnBind(true, 60000), "boot option does not affect late service binds");
        check(!HomeLaunchPolicy.launchOnBind(false, 0), "first boot also requires opt-in");
        System.out.println(checks + " native Home policy checks passed.");
    }
}
