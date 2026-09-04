package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The spare target as a load prediction: it climbs while the label cannot keep up, holds while the
 * label is being served, and fades back to the base count once the work stops.
 */
class HotSpareDemandTest {

    private static final String LABEL = "linux";

    private MovableClock clock;
    private EC2Cloud cloud;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        HotSpareDemand.clock = clock;
        HotSpareDemand.reset();
        cloud = new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(), null, null);
    }

    @AfterEach
    void tearDown() {
        HotSpareDemand.clock = Clock.systemDefaultZone();
        HotSpareDemand.reset();
    }

    /**
     * The reported behaviour: a step of 5 has to mean five spares held for as long as the label is
     * busy, not five agents handed out once. A spare running a build is not a spare, so the target
     * stays where it is and the shortfall is reported again.
     */
    @Test
    void testTheTargetIsHeldWhileTheSparesAreBeingConsumed() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        // Five builds arrive with nothing warm to take them.
        assertThat(demand.updateTarget(config, 0, 0, 5, 0), equalTo(5));

        // They are all running now, so the label holds no spares at all and needs five again.
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 0, 0, 5), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(
                "the target must not fade while the label is doing work",
                demand.updateTarget(config, 0, 5, 0, 5),
                equalTo(5));
    }

    /**
     * A label nobody has asked for costs nothing, so the first build to take an executor is what
     * warms it up. Nothing is queued and nothing is waiting at that point: the build in question
     * already has its agent. The spares are for the builds after it.
     */
    @Test
    void testTakingAnExecutorWarmsTheLabelUpFromNothing() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat("nothing is asking for the label yet", demand.updateTarget(config, 0, 0, 0, 0), equalTo(0));

        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));
    }

    /**
     * The point of reacting to an executor being taken: the pool is one short the moment a build
     * starts, and the target says so, so the replacement is provisioned while that build runs
     * rather than after the next build has already had to wait.
     */
    @Test
    void testEveryExecutorTakenLeavesTheLabelAShortfallToReplace() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        // Five spares are warm, and builds start taking them one at a time.
        for (int taken = 1; taken <= 4; taken++) {
            clock.advanceSeconds(10);
            HotSpareDemand.spareConsumed(cloud, config);
            int sparesLeft = 5 - taken;
            assertThat(
                    "the target covers the spares taken, so each one is replaced",
                    demand.updateTarget(config, sparesLeft, taken, 0, taken),
                    equalTo(5));
        }
    }

    /**
     * Builds taking the last of the spares means the replacements are not arriving fast enough, so
     * the label climbs a step even though no build has had to queue for it yet.
     */
    @Test
    void testRunningTheSparesDryGrowsTheTarget() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        clock.advanceMinutes(1);
        for (int i = 0; i < 5; i++) {
            HotSpareDemand.spareConsumed(cloud, config);
        }
        assertThat("nothing warm is left, so hold more of it", demand.updateTarget(config, 0, 5, 0, 6), equalTo(10));

        clock.advanceMinutes(1);
        for (int i = 0; i < 5; i++) {
            HotSpareDemand.spareConsumed(cloud, config);
        }
        assertThat(demand.updateTarget(config, 0, 10, 0, 11), equalTo(15));
    }

    /**
     * Spares being taken while others stay warm is exactly what the label is meant to do, so the
     * target holds instead of climbing.
     */
    @Test
    void testSparesBeingTakenWithWarmOnesLeftHoldsTheTarget() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        clock.advanceMinutes(5);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 4, 1, 0, 2), equalTo(5));
        clock.advanceMinutes(5);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 4, 1, 0, 3), equalTo(5));
    }

    /**
     * The builds stop, so the label goes back to costing nothing.
     */
    @Test
    void testALabelThatStopsBeingUsedFadesAway() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(15);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);
        HotSpareDemand.spareConsumed(cloud, config);
        assertThat(demand.updateTarget(config, 0, 0, 0, 1), equalTo(5));

        // The last build finishes. Nothing has taken an executor since.
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    /**
     * The label cannot keep up, so the target climbs a step at a time: 5, 10, 15, 20.
     */
    @Test
    void testTheTargetGrowsAStepAtATimeWhileTheSparesCannotKeepUp() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 30, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 30, 0), equalTo(10));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 10, 30, 0), equalTo(15));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 15, 30, 0), equalTo(20));
    }

    /**
     * Growth is what the instances already on their way cannot cover, so once they can, the target
     * settles instead of chasing the queue.
     */
    @Test
    void testTheTargetSettlesOnceTheInstancesOnTheirWayCoverTheQueue() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 3, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 3, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 5, 0, 3, 0), equalTo(5));
    }

    /**
     * Checks are also triggered by events such as a build starting, so a burst must not be able to
     * step the target up several times before the first instances have had a chance to boot.
     */
    @Test
    void testRepeatedChecksWithinAMinuteOnlyGrowTheTargetOnce() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 50, 0), equalTo(5));
        clock.advanceSeconds(5);
        assertThat(demand.updateTarget(config, 0, 0, 50, 0), equalTo(5));
        clock.advanceSeconds(5);
        assertThat(demand.updateTarget(config, 0, 0, 50, 0), equalTo(5));

        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 0, 50, 0), equalTo(10));
    }

    /**
     * With nothing asking for the label, the target gives up a step per idle timeout until the
     * label costs nothing.
     */
    @Test
    void testAQuietLabelFadesToTheBaseCount() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(15);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 20, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 20, 0), equalTo(10));

        // The work stops. The first quiet check only starts the clock.
        assertThat(demand.updateTarget(config, 10, 0, 0, 0), equalTo(10));
        clock.advanceMinutes(14);
        assertThat("not a whole idle timeout yet", demand.updateTarget(config, 10, 0, 0, 0), equalTo(10));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 10, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
        clock.advanceMinutes(15);
        assertThat("the base count is the floor", demand.updateTarget(config, 0, 0, 0, 0), equalTo(0));
    }

    @Test
    void testAQuietLabelFadesNoFurtherThanItsBaseCount() {
        HotSpareConfigByLabel config = rule(2, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(
                "the base count applies before anything is asked of the label",
                demand.updateTarget(config, 0, 0, 0, 0),
                equalTo(2));
        assertThat(demand.updateTarget(config, 0, 0, 4, 0), equalTo(7));

        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 7, 0, 0, 0), equalTo(7));
        clock.advanceMinutes(15);
        assertThat(demand.updateTarget(config, 7, 0, 0, 0), equalTo(2));
        clock.advanceMinutes(30);
        assertThat(demand.updateTarget(config, 2, 0, 0, 0), equalTo(2));
    }

    /**
     * A spare that sat unused for a whole idle timeout says the target overshot. Stepping down as
     * they are reclaimed is what stops the label replacing the agents it is giving up.
     */
    @Test
    void testAReclaimedSpareLowersTheTarget() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 30, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 30, 0), equalTo(10));

        HotSpareDemand.spareReclaimed(cloud, config);
        assertThat(demand.getTarget(), equalTo(5));
        HotSpareDemand.spareReclaimed(cloud, config);
        assertThat(demand.getTarget(), equalTo(0));
        HotSpareDemand.spareReclaimed(cloud, config);
        assertThat("nothing below the base count", demand.getTarget(), equalTo(0));
    }

    @Test
    void testTheCeilingLimitsHowFarTheTargetCanGrow() {
        HotSpareConfigByLabel config = rule(0, 5, 7);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 40, 0), equalTo(5));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 5, 40, 0), equalTo(7));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 7, 40, 0), equalTo(7));
    }

    /**
     * A label whose templates are all at their instance caps never keeps up, so growth has to stop
     * at the work in sight. Otherwise the target would climb for as long as the caps held and then
     * try to launch that imagined backlog as soon as capacity appeared.
     */
    @Test
    void testGrowthStopsAtTheWorkInSight() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        for (int minute = 0; minute < 10; minute++) {
            demand.updateTarget(config, 0, 0, 2, 0);
            clock.advanceMinutes(1);
        }

        assertThat("two queued builds plus a step of cover", demand.getTarget(), equalTo(7));
    }

    /**
     * A rule with no step would otherwise be stuck at its base count forever, unable to react to
     * anything.
     */
    @Test
    void testARuleWithNoStepStillMovesByOne() {
        HotSpareConfigByLabel config = rule(0, 0, null);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(1));
        clock.advanceMinutes(1);
        assertThat(demand.updateTarget(config, 0, 1, 10, 0), equalTo(2));
    }

    /**
     * Agents that are never idle-terminated have no rate to follow, so the fade falls back to the
     * default idle timeout rather than never happening.
     */
    @Test
    void testALabelWhoseAgentsNeverTimeOutStillFades() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(0);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(5));
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(HotSpareConfigByLabel.DEFAULT_IDLE_TIMEOUT_MINUTES);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    /**
     * A negative idle timeout is a billing-period timeout, whose magnitude is still the rate the
     * agents disappear at.
     */
    @Test
    void testABillingPeriodIdleTimeoutFadesAtItsOwnRate() {
        HotSpareConfigByLabel config = rule(0, 5, null);
        config.setIdleTimeoutMinutes(-10);
        HotSpareDemand demand = HotSpareDemand.of(cloud, LABEL);

        assertThat(demand.updateTarget(config, 0, 0, 10, 0), equalTo(5));
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(5));
        clock.advanceMinutes(10);
        assertThat(demand.updateTarget(config, 5, 0, 0, 0), equalTo(0));
    }

    @Test
    void testEachLabelIsPredictedSeparately() {
        HotSpareConfigByLabel linux = rule(0, 5, null);
        HotSpareConfigByLabel windows = new HotSpareConfigByLabel("windows");
        windows.setScalingFactor(2);

        assertThat(HotSpareDemand.of(cloud, LABEL).updateTarget(linux, 0, 0, 10, 0), equalTo(5));
        assertThat(HotSpareDemand.of(cloud, "windows").updateTarget(windows, 0, 0, 10, 0), equalTo(2));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(5));
    }

    private static HotSpareConfigByLabel rule(int baseHotSpares, int step, Integer maxHotSpares) {
        HotSpareConfigByLabel config = new HotSpareConfigByLabel(LABEL);
        config.setBaseHotSpares(baseHotSpares);
        config.setScalingFactor(step);
        config.setMaxHotSpares(maxHotSpares);
        return config;
    }

    private static final class MovableClock extends Clock {

        private long millis = TimeUnit.DAYS.toMillis(1);

        void advanceMinutes(long minutes) {
            millis += TimeUnit.MINUTES.toMillis(minutes);
        }

        void advanceSeconds(long seconds) {
            millis += TimeUnit.SECONDS.toMillis(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
