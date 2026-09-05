package hudson.plugins.ec2.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.HotSpareConfigByLabel;
import hudson.plugins.ec2.HotSpareDemand;
import hudson.plugins.ec2.SlaveTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public class MinimumInstanceChecker {

    private static final Logger LOGGER = Logger.getLogger(MinimumInstanceChecker.class.getName());

    /**
     * Executor for deferred minimum-instance checks. Heavy provisioning (EC2 API, cloud.provision)
     * runs here so callers (taskAccepted, EC2SlaveMonitor) return immediately.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MinimumInstanceChecker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Set while a check is queued but has not started reading the state of Jenkins yet.
     */
    private static final AtomicBoolean CHECK_QUEUED = new AtomicBoolean();

    /**
     * Schedules a minimum-instance check to run asynchronously. Use this instead of
     * {@link #checkForMinimumInstances()} when the caller must return immediately (e.g. taskAccepted).
     *
     * <p>Requests that arrive while one is already waiting are dropped: the queued check has not
     * looked at Jenkins yet, so it will see everything they would have asked about. Without this, a
     * burst of builds starting at once would queue one redundant pass per build behind the single
     * checker thread.
     */
    public static void scheduleCheck() {
        if (!CHECK_QUEUED.compareAndSet(false, true)) {
            return;
        }
        try {
            EXECUTOR.execute(() -> {
                CHECK_QUEUED.set(false);
                checkForMinimumInstances();
            });
        } catch (RuntimeException e) {
            // Jenkins is shutting down. Leaving the flag set would silence every later request.
            CHECK_QUEUED.set(false);
            throw e;
        }
    }

    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Needs to be overridden from tests")
    public static Clock clock = Clock.systemDefaultZone();

    private static Stream<EC2Computer> agentsForTemplate(@NonNull SlaveTemplate agentTemplate) {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = computer.getSlaveTemplate();
                    return computerTemplate != null
                            && Objects.equals(computerTemplate.description, agentTemplate.description);
                });
    }

    public static int countCurrentNumberOfAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) agentsForTemplate(agentTemplate).count();
    }

    private static Stream<EC2Computer> idleAgents(@NonNull SlaveTemplate agentTemplate) {
        return agentsForTemplate(agentTemplate).filter(Computer::isIdle);
    }

    public static int countCurrentNumberOfSpareAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate).filter(Computer::isOnline).count();
    }

    /**
     * @return the number of agents of this template that exist but cannot take work yet. Counted
     *     from attachment rather than from the launcher starting, for the reason given on
     *     {@link #countCurrentNumberOfProvisioningAgentsForLabel}.
     */
    public static int countCurrentNumberOfProvisioningAgents(@NonNull SlaveTemplate agentTemplate) {
        return (int) idleAgents(agentTemplate)
                .filter(Computer::isOffline)
                .filter(computer -> !computer.isTemporarilyOffline())
                .count();
    }

    /*
        Get the number of queued builds that match an AMI (agentTemplate)
    */
    public static int countQueueItemsForAgentTemplate(@NonNull SlaveTemplate agentTemplate) {
        return (int) Queue.getInstance().getBuildableItems().stream()
                .map((Queue.Item item) -> item.getAssignedLabel())
                .filter(Objects::nonNull)
                .filter((Label label) -> label.matches(agentTemplate.getLabelSet()))
                .count();
    }

    /**
     * Agents of a whole label group. A hot spare rule owns the label rather than one AMI, so its
     * counts aggregate over every template of the cloud carrying that label instead of matching a
     * single template description.
     */
    private static Stream<EC2Computer> agentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        Set<String> descriptions = cloud.getTemplates(label).stream()
                .map(template -> template.description)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .filter(computer -> {
                    SlaveTemplate computerTemplate = computer.getSlaveTemplate();
                    return computerTemplate != null && descriptions.contains(computerTemplate.description);
                });
    }

    public static int countCurrentNumberOfSpareAgentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(Computer::isIdle)
                .filter(Computer::isOnline)
                .count();
    }

    /**
     * @return the number of agents of the label group that exist but cannot take work yet.
     *     <p>An agent is counted from the moment it is attached rather than from the moment its
     *     launcher starts, because {@link Computer#isConnecting()} is false for the gap in between
     *     and two passes falling either side of that gap would launch the same shortfall twice.
     *     Passes are triggered by builds queueing and starting, so they do arrive in quick
     *     succession. An agent that never comes up is dealt with by its grace period.
     */
    public static int countCurrentNumberOfProvisioningAgentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(Computer::isIdle)
                .filter(Computer::isOffline)
                .filter(computer -> !computer.isTemporarilyOffline())
                .count();
    }

    /**
     * @return the number of agents of the label group running a build. They are not spares, which
     *     is why a spare taken by a build is replaced, but they do say the label is in use.
     */
    public static int countCurrentNumberOfBusyAgentsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        return (int) agentsForLabel(cloud, label)
                .filter(computer -> !computer.isIdle())
                .count();
    }

    /**
     * @return the number of buildable queue items any template of the label group could serve.
     */
    public static int countQueueItemsForLabel(@NonNull EC2Cloud cloud, @NonNull Label label) {
        Collection<SlaveTemplate> templates = cloud.getTemplates(label);
        return (int) Queue.getInstance().getBuildableItems().stream()
                .map((Queue.Item item) -> item.getAssignedLabel())
                .filter(Objects::nonNull)
                .filter(assigned -> templates.stream().anyMatch(template -> assigned.matches(template.getLabelSet())))
                .count();
    }

    /**
     * Checks all EC2 cloud templates and provisions agents to meet minimum instance requirements.
     * Synchronized to prevent concurrent provisioning decisions that could lead to over-provisioning
     * when multiple agents accept tasks simultaneously.
     *
     * @see <a href="https://issues.jenkins.io/browse/JENKINS-76171">JENKINS-76171</a>
     */
    public static synchronized void checkForMinimumInstances() {
        Jenkins jenkins = Jenkins.get();

        // Early exit if nothing asks for instances to be kept warm
        boolean hasMinimumRequirements = jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .anyMatch(cloud -> !cloud.getHotSpareConfigsByLabel().isEmpty()
                        || cloud.getTemplates().stream()
                                .anyMatch(template -> template.getMinimumNumberOfInstances() > 0
                                        || template.getMinimumNumberOfSpareInstances() > 0));

        if (!hasMinimumRequirements) {
            // Neither minimum instances nor label hot spares are configured - exit immediately
            return;
        }

        jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().forEach(agentTemplate -> {
                    // Minimum instances now have a time range, check to see
                    // if we are within that time range and return early if not.
                    if (!minimumInstancesActive(agentTemplate.getMinimumNumberOfInstancesTimeRangeConfig())) {
                        return;
                    }
                    int requiredMinAgents = agentTemplate.getMinimumNumberOfInstances();
                    int requiredMinSpareAgents = agentTemplate.getMinimumNumberOfSpareInstances();
                    int currentNumberOfAgentsForTemplate = countCurrentNumberOfAgents(agentTemplate);
                    int currentNumberOfSpareAgentsForTemplate = countCurrentNumberOfSpareAgents(agentTemplate);
                    int currentNumberOfProvisioningAgentsForTemplate =
                            countCurrentNumberOfProvisioningAgents(agentTemplate);
                    int currentBuildsWaitingForTemplate = countQueueItemsForAgentTemplate(agentTemplate);
                    int provisionForMinAgents = 0;
                    int provisionForMinSpareAgents = 0;

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of agents
                    provisionForMinAgents = requiredMinAgents - currentNumberOfAgentsForTemplate;
                    if (provisionForMinAgents < 0) {
                        provisionForMinAgents = 0;
                    }

                    // Check if we need to provision any agents because we
                    // don't have the minimum number of spare agents.
                    // Don't double provision if minAgents and minSpareAgents are set.
                    if (requiredMinSpareAgents > 0) {
                        provisionForMinSpareAgents = (requiredMinSpareAgents + currentBuildsWaitingForTemplate)
                                - (currentNumberOfSpareAgentsForTemplate
                                        + provisionForMinAgents
                                        + currentNumberOfProvisioningAgentsForTemplate);
                        if (provisionForMinSpareAgents < 0) {
                            provisionForMinSpareAgents = 0;
                        }
                    }

                    int numberToProvision = provisionForMinAgents + provisionForMinSpareAgents;

                    if (numberToProvision > 0 || requiredMinAgents > 0 || requiredMinSpareAgents > 0) {
                        LOGGER.log(
                                Level.FINE,
                                "MinimumInstanceChecker for template {0}: toProvision={1}",
                                new Object[] {agentTemplate.description, numberToProvision});
                    }

                    if (numberToProvision > 0) {
                        cloud.provision(agentTemplate, numberToProvision);
                    }
                }));

        jenkins.clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(MinimumInstanceChecker::checkForLabelHotSpares);
    }

    /**
     * Tops up the hot spares for every label rule of a cloud. Runs inside
     * {@link #checkForMinimumInstances()} rather than from a monitor of its own so all provisioning
     * decisions stay behind the same lock (JENKINS-76171).
     *
     * <p>A rule owns the spare count for its label, so a template's
     * {@link SlaveTemplate#getMinimumNumberOfSpareInstances()} no longer applies to the templates
     * the rule covers.
     *
     * <p>Only idle agents count towards the target, so a spare taken by a build is a shortfall to be
     * replaced, which is what keeps a label warm for the whole of a busy period instead of draining
     * with the first few builds. Taking an executor schedules this pass
     * ({@link hudson.plugins.ec2.EC2RetentionStrategy#taskAccepted}), so the replacement launches
     * while the build that took the spare is still starting. Instances already on their way are
     * subtracted as well, otherwise every pass would re-provision the same shortfall while the
     * previous batch is still booting. {@link HotSpareDemand} decides what the target itself
     * should be.
     */
    private static void checkForLabelHotSpares(@NonNull EC2Cloud cloud) {
        for (HotSpareConfigByLabel config : cloud.getHotSpareConfigsByLabel()) {
            String labelName = config.getLabel();
            if (labelName == null) {
                continue;
            }
            Label label = Label.get(labelName);
            Collection<SlaveTemplate> matching = cloud.getTemplates(label);
            if (matching.isEmpty()) {
                continue;
            }

            int currentSpares = countCurrentNumberOfSpareAgentsForLabel(cloud, label);
            int currentProvisioning = countCurrentNumberOfProvisioningAgentsForLabel(cloud, label);
            int busyAgents = countCurrentNumberOfBusyAgentsForLabel(cloud, label);
            int queuedBuilds = countQueueItemsForLabel(cloud, label);

            int target = HotSpareDemand.of(cloud, labelName)
                    .updateTarget(config, currentSpares, currentProvisioning, queuedBuilds, busyAgents);
            int toLaunch = target - (currentSpares + currentProvisioning);

            LOGGER.log(
                    Level.FINE,
                    "Hot spares for label {0}: target={1}, spare={2}, provisioning={3}, busy={4}, queued={5}, toLaunch={6}",
                    new Object[] {
                        labelName, target, currentSpares, currentProvisioning, busyAgents, queuedBuilds, toLaunch
                    });

            if (toLaunch > 0) {
                provisionAcrossLabelGroup(cloud, label, matching, toLaunch);
            }
        }
    }

    /**
     * Spreads a shortfall over the templates of a label group, in rotation order so hot spare
     * weights apply, and never beyond what a template's instance cap allows. A template at its cap
     * is skipped for this round and its share is offered to the templates that still have room.
     */
    private static void provisionAcrossLabelGroup(
            @NonNull EC2Cloud cloud, @NonNull Label label, @NonNull Collection<SlaveTemplate> matching, int toLaunch) {
        for (SlaveTemplate template : cloud.orderTemplatesForLabel(label, matching)) {
            if (toLaunch <= 0) {
                return;
            }
            int headroom = cloud.getAvailableCapacity(template);
            if (headroom <= 0) {
                LOGGER.log(
                        Level.FINE,
                        "{0} is at its instance cap, offering its hot spares to the next template",
                        template);
                continue;
            }
            int number = Math.min(toLaunch, headroom);
            cloud.provision(template, number);
            toLaunch -= number;
        }
        if (toLaunch > 0) {
            LOGGER.log(
                    Level.FINE,
                    "{0} hot spare(s) for label {1} were not provisioned: every template is at its instance cap",
                    new Object[] {toLaunch, label.getName()});
        }
    }

    public static boolean minimumInstancesActive(
            MinimumNumberOfInstancesTimeRangeConfig minimumNumberOfInstancesTimeRangeConfig) {
        if (minimumNumberOfInstancesTimeRangeConfig == null) {
            return true;
        }
        LocalTime fromTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeFromAsTime();
        LocalTime toTime = minimumNumberOfInstancesTimeRangeConfig.getMinimumNoInstancesActiveTimeRangeToAsTime();

        LocalDateTime now = LocalDateTime.now(clock);
        LocalTime nowTime = LocalTime.from(now); // No date. Easier for comparison on time only.

        boolean passingMidnight = false;
        if (toTime.isBefore(fromTime)) {
            passingMidnight = true;
        }

        if (passingMidnight) {
            if (nowTime.isAfter(fromTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            } else if (nowTime.isBefore(toTime)) {
                // We've gone past midnight and want to check yesterday's setting.
                String yesterday = now.minusDays(1).getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(yesterday);
            }
        } else {
            if (nowTime.isAfter(fromTime) && nowTime.isBefore(toTime)) {
                String today = now.getDayOfWeek().name().toLowerCase();
                return minimumNumberOfInstancesTimeRangeConfig.getDay(today);
            }
        }
        return false;
    }

    @Terminator
    public static void discardIdleInstances() throws Exception {
        LOGGER.fine("Looking for idle instances to discard");
        List<Future<?>> futures = new ArrayList<>();
        Jenkins.get().clouds.stream()
                .filter(EC2Cloud.class::isInstance)
                .map(EC2Cloud.class::cast)
                .forEach(cloud -> cloud.getTemplates().stream()
                        .filter(SlaveTemplate::getTerminateIdleDuringShutdown)
                        .forEach(agentTemplate -> idleAgents(agentTemplate).forEach(computer -> {
                            EC2AbstractSlave agent = computer.getNode();
                            if (agent != null) {
                                LOGGER.info(() -> "discarding idle instance " + agent.getInstanceId());
                                futures.add(agent.terminate());
                            }
                        })));
        // Must wait; otherwise task could run too late during shutdown, leading to NoClassDefFoundError.
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
    }
}
