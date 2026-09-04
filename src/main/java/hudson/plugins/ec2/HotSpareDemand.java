/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * How many idle spares a label should be holding right now, learned from how well the spares have
 * been keeping up.
 *
 * <p>A fixed number of spares is either wasted while nothing is building or exhausted the moment a
 * burst arrives, so the number is treated as a load prediction that follows demand:
 *
 * <ul>
 *   <li>every spare taken by a build is replaced, because a busy agent is not a spare. This is what
 *       keeps a label warm for the whole of a build rather than for the first few;
 *   <li>when builds are waiting that neither the spares nor the instances already on their way can
 *       take, the target grows by {@link HotSpareConfigByLabel#getScalingFactor()}, so a label that
 *       cannot keep up climbs 5, 10, 15, 20 until it does;
 *   <li>while builds are running or queued the target is held, so a label that has found its level
 *       stays there;
 *   <li>growth stops at the work in sight, so a label whose instance caps are already reached
 *       does not accumulate a backlog it would try to launch all at once later;
 *   <li>a spare reclaimed by the idle timeout means the target overshot, so it comes back down by
 *       the same step. A label with nothing to do also steps down once per idle timeout, which
 *       drains it to {@link HotSpareConfigByLabel#getBaseHotSpares()} - zero by default - rather
 *       than paying for spares nothing is asking for.
 * </ul>
 *
 * <p>The target is deliberately not persisted: after a restart the label starts from its base count
 * and learns again within a few minutes, which is safer than restoring a number that described a
 * load that has since gone away.
 */
@Restricted(NoExternalUse.class)
public final class HotSpareDemand {

    private static final Logger LOGGER = Logger.getLogger(HotSpareDemand.class.getName());

    /**
     * How long a growth step has to be given before another one is allowed. Provisioning is not
     * instant and a check can be triggered by events rather than by the clock, so without this a
     * single burst would step the target up several times before the first instances even boot.
     */
    private static final long GROWTH_INTERVAL_MS = Long.getLong("jenkins.ec2.hotSpareGrowthIntervalMs", 60_000);

    private static final Map<String, HotSpareDemand> DEMANDS = new ConcurrentHashMap<>();

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private int target;

    private long lastGrowthMillis;

    /** When the label was first seen with nothing to do, or 0 while it has work. */
    private long quietSinceMillis;

    private HotSpareDemand() {}

    public static HotSpareDemand of(@NonNull EC2Cloud cloud, @NonNull String label) {
        return DEMANDS.computeIfAbsent(cloud.name + '\u0000' + label, key -> new HotSpareDemand());
    }

    /**
     * Forgets every learned target. For tests, and for callers that have to reason about a cloud
     * whose configuration has been replaced.
     */
    public static void reset() {
        DEMANDS.clear();
    }

    /**
     * Moves the target in response to what the label looks like right now.
     *
     * @param spares idle agents of the label group that could take work immediately.
     * @param provisioning instances of the label group on their way to becoming spares.
     * @param queued builds waiting for the label.
     * @param busy agents of the label group currently running a build.
     * @return the number of idle spares the label should hold.
     */
    public synchronized int updateTarget(
            @NonNull HotSpareConfigByLabel config, int spares, int provisioning, int queued, int busy) {
        final int base = config.getBaseHotSpares();
        final int step = growthStep(config);
        final long now = clock.millis();

        target = Math.max(target, base);

        /*
         * Waiting builds that nothing warm and nothing in flight can take are the signal that the
         * spares are not keeping up. Counting what is in flight is what makes the loop settle: a
         * burst grows the target once per interval until the instances already coming cover it.
         */
        boolean notKeepingUp = queued > spares + provisioning;
        boolean hasWork = queued > 0 || busy > 0;

        if (notKeepingUp) {
            quietSinceMillis = 0;
            if (now - lastGrowthMillis >= GROWTH_INTERVAL_MS) {
                lastGrowthMillis = now;
                int grown = Math.min(Math.min(ceiling(config), demandCeiling(config, queued, busy)), target + step);
                if (grown > target) {
                    LOGGER.log(
                            Level.FINE,
                            "Hot spares for {0} are not keeping up with {1} queued build(s), "
                                    + "raising the target from {2} to {3}",
                            new Object[] {config.getLabel(), queued, target, grown});
                    target = grown;
                }
            }
        } else if (hasWork) {
            // Builds are being served: this is the level the label needs, so stay there.
            quietSinceMillis = 0;
        } else {
            if (quietSinceMillis == 0) {
                quietSinceMillis = now;
            } else if (now - quietSinceMillis >= decayIntervalMillis(config)) {
                quietSinceMillis = now;
                stepDown(config, "nothing has asked for the label for a whole idle timeout");
            }
        }

        target = Math.min(target, ceiling(config));
        return target;
    }

    /**
     * Reports that a spare of this label was reclaimed for being idle, which means the target was
     * higher than the work needed and should come down.
     *
     * <p>Stepping down here rather than only on a timer is what stops a label from replacing the
     * spares it is in the middle of giving up: the step is the same size as the growth step, so it
     * outruns the agents timing out one by one.
     */
    public static void spareReclaimed(@NonNull EC2Cloud cloud, @NonNull HotSpareConfigByLabel config) {
        String label = config.getLabel();
        if (label == null) {
            return;
        }
        HotSpareDemand demand = of(cloud, label);
        synchronized (demand) {
            demand.quietSinceMillis = clock.millis();
            demand.stepDown(config, "an idle spare was reclaimed");
        }
    }

    public synchronized int getTarget() {
        return target;
    }

    private void stepDown(HotSpareConfigByLabel config, String why) {
        int lowered = Math.max(config.getBaseHotSpares(), target - growthStep(config));
        if (lowered != target) {
            LOGGER.log(Level.FINE, "Lowering the hot spare target for {0} from {1} to {2}: {3}", new Object[] {
                config.getLabel(), target, lowered, why
            });
            target = lowered;
        }
    }

    /**
     * @return the amount the target moves by. A rule configured with no step would never move, so
     *     it still creeps by one rather than freezing at its base count.
     */
    private static int growthStep(HotSpareConfigByLabel config) {
        return Math.max(1, config.getScalingFactor());
    }

    private static int ceiling(HotSpareConfigByLabel config) {
        Integer max = config.getMaxHotSpares();
        return max == null ? Integer.MAX_VALUE : Math.max(0, max);
    }

    /**
     * @return how far the work in sight can justify growing to: what the label is being asked for,
     *     plus one step of cover for what arrives next. Without this, a label whose templates are
     *     all at their instance caps would keep failing to keep up and keep growing, and would then
     *     try to launch that whole imagined backlog the moment capacity appeared.
     */
    private static int demandCeiling(HotSpareConfigByLabel config, int queued, int busy) {
        return Math.max(config.getBaseHotSpares(), queued + busy + growthStep(config));
    }

    /**
     * @return how long a label has to be quiet before it gives up a step. The idle timeout is the
     *     admin's own statement of how long an unused agent is worth keeping, so the prediction
     *     fades at the same rate the agents behind it do.
     */
    private static long decayIntervalMillis(HotSpareConfigByLabel config) {
        int minutes = Math.abs(config.getIdleTimeoutMinutes());
        if (minutes == 0) {
            // The agents are never idle-terminated, so there is no rate to follow. Fall back to the
            // default idle timeout, otherwise a target raised once would never come back down.
            minutes = HotSpareConfigByLabel.DEFAULT_IDLE_TIMEOUT_MINUTES;
        }
        return TimeUnit.MINUTES.toMillis(minutes);
    }
}
