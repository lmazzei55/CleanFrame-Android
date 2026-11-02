package com.mazzei.cleanframe;

/** Shared pure policy: delivery cannot make an expired source sample fresh again. */
final class VacuumFeedPolicy {
    static final long TTL_MS = 90000;
    static long sourceAge(double receivedAt, long now) {
        double age = now - receivedAt;
        return !Double.isNaN(age) && !Double.isInfinite(age) && age >= -5000 && age <= TTL_MS ? Math.max(0, (long) age) : TTL_MS + 1;
    }
    static boolean fresh(long ageAtArrival, long elapsedSinceArrival, boolean sameBoot) {
        return sameBoot && ageAtArrival >= 0 && elapsedSinceArrival >= 0 && ageAtArrival <= TTL_MS && elapsedSinceArrival <= TTL_MS - ageAtArrival;
    }
}
