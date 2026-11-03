package com.mazzei.cleanframe;
public final class VacuumFeedPolicyTest {
    private static int count;
    private static void check(boolean value) { if (!value) throw new AssertionError("Check " + (count + 1)); count++; }
    public static void main(String[] args) {
        check(VacuumFeedPolicy.sourceAge(1000, 1000) == 0);
        check(VacuumFeedPolicy.sourceAge(0, 100000) > 90000);
        check(VacuumFeedPolicy.sourceAge(20000, 1000) > 90000);
        check(VacuumFeedPolicy.sourceAge(Double.NaN, 1000) > 90000);
        check(VacuumFeedPolicy.fresh(30000, 60000, true));
        check(!VacuumFeedPolicy.fresh(30000, 60001, true));
        check(!VacuumFeedPolicy.fresh(90001, 0, true));
        check(!VacuumFeedPolicy.fresh(0, 0, false));
        check(!VacuumFeedPolicy.fresh(0, -1, true));
        check(!VacuumFeedPolicy.fresh(Long.MAX_VALUE, Long.MAX_VALUE, true));
        System.out.println(count + " native vacuum freshness checks passed.");
    }
}
