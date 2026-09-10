package hudson.plugins.ec2.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import hudson.ExtensionList;
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2RetentionStrategy;
import hudson.plugins.ec2.EbsEncryptRootVolume;
import hudson.plugins.ec2.HotSpareConfigByLabel;
import hudson.plugins.ec2.HotSpareDemand;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.Tenancy;
import hudson.slaves.NodeProvisioner;
import java.security.Security;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Hot spare scaling driven by a label rule rather than by a single template.
 */
@WithJenkins
class LabelHotSpareCheckerTest {

    private static final String LABEL = "linux";

    /**
     * No test here wants a build to actually start: what is being measured is the spares a queue
     * warms up, and a branch being picked up by an agent moves it out of the queue and the agent
     * out of the pool, mid-count. Vetoing every assignment keeps the queue still.
     */
    @TestExtension
    public static class KeepEveryBuildQueued extends QueueTaskDispatcher {

        @Override
        public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "held in the queue by LabelHotSpareCheckerTest";
                }
            };
        }
    }

    private JenkinsRule r;

    private MovableClock clock;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        // The mock client and its instance list are static, so a fresh one keeps the instances of
        // one test from counting against the caps of the next.
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
        clock = new MovableClock();
        HotSpareDemand.clock = clock;
        HotSpareDemand.reset();
        // Jenkins provisions for a queued build on its own account, off a timer, and these tests now
        // react to the same queue. Its agents would be indistinguishable from spares in the counts
        // below, so the tests that use a real build measure only what the hot spare pass launched.
        ExtensionList<NodeProvisioner.Strategy> strategies = r.jenkins.getExtensionList(NodeProvisioner.Strategy.class);
        strategies.removeAll(new ArrayList<>(strategies));
    }

    @AfterEach
    void tearDown() {
        HotSpareDemand.clock = Clock.systemDefaultZone();
        HotSpareDemand.reset();
    }

    /**
     * The point of a hot spare is to exist before the work does, so a base count must provision
     * with an empty queue.
     */
    @Test
    void testBaseHotSparesAreProvisionedWithAnEmptyQueue() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * The spares already held are subtracted, so repeated ticks converge instead of provisioning
     * the same shortfall again.
     */
    @Test
    void testRepeatedChecksDoNotProvisionTheSameShortfallTwice() throws Exception {
        HotSpareConfigByLabel rule = rule(2, 0, null);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(2));
    }

    /**
     * The ceiling applies to the label group as a whole, not to each template in it.
     */
    @Test
    void testMaxHotSparesCapsTheGroupTotal() throws Exception {
        HotSpareConfigByLabel rule = rule(5, 0, 3);
        cloud(rule, template("first", 10), template("second", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(3));
    }

    /**
     * Spares are counted across every template carrying the label, so a group that already holds
     * enough is left alone even though no single template does.
     */
    @Test
    void testSparesAreCountedAcrossTheWholeLabelGroup() throws Exception {
        HotSpareConfigByLabel rule = rule(4, 0, null);
        cloud(rule, template("first", 1), template("second", 3));

        MinimumInstanceChecker.checkForMinimumInstances();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(4));
        assertThat(
                "each template should stay within its own instance cap",
                agentsByTemplate(),
                equalTo(Map.of("first", 1L, "second", 3L)));
    }

    /**
     * A rule for a label none of the templates carries provisions nothing.
     */
    @Test
    void testRuleForAnUnknownLabelProvisionsNothing() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel("no-such-label");
        rule.setBaseHotSpares(3);
        cloud(rule, template("only", 10));

        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat(countAgents(), equalTo(0));
    }

    /**
     * The reported defect: a step of 5 used to hand out five agents once and then let the label
     * drain, because the target was derived from the queue and the queue empties as soon as the
     * builds start. The target has to survive the queue emptying, and the spares consumed have to
     * be replaced.
     */
    @Test
    void testSparesAreReplacedAsTheyAreConsumed() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        SlaveTemplate template = template("only", 20);
        // Replacements have to be new instances here, or the mock hands back the ones the departed
        // agents left behind and the test cannot tell provisioning from adoption.
        template.setAvoidUsingOrphanedNodes(true);
        EC2Cloud cloud = cloud(rule, template);
        // A burst the label could not keep up with, which is what raises the target to five.
        HotSpareDemand.of(cloud, LABEL).updateTarget(rule, 0, 0, 5, 0);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(countAgents(), equalTo(5));

        // The builds take all five, so the label holds nothing warm again.
        removeAllAgents();
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("the five spares should have been replaced", countAgents(), equalTo(5));
        assertThat("five more instances launched", AmazonEC2FactoryMockImpl.instances.size(), equalTo(10));
    }

    /**
     * Nothing has asked for the label for longer than the idle timeout, so it should stop paying
     * for spares altogether rather than holding the level its last burst asked for.
     */
    @Test
    void testAQuietLabelStopsReplacingSpares() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        rule.setIdleTimeoutMinutes(15);
        EC2Cloud cloud = cloud(rule, template("only", 20));
        HotSpareDemand.of(cloud, LABEL).updateTarget(rule, 0, 0, 5, 0);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat(countAgents(), equalTo(5));

        removeAllAgents();
        clock.advanceMinutes(16);
        MinimumInstanceChecker.checkForMinimumInstances();

        assertThat("the prediction should have faded to nothing", countAgents(), equalTo(0));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(0));
    }

    /**
     * The label with nothing warm is the case the spares exist for, and it is the one case the
     * agents cannot report themselves: with no capacity, no executor is taken. Queueing a build has
     * to be enough on its own, or the first build of the day waits for the periodic sweep before
     * anything is even asked for.
     */
    @Test
    void testQueueingABuildProvisionsSparesWithoutWaitingForASweep() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 3, null);
        EC2Cloud cloud = cloud(rule, template("only", 20));

        r.jenkins.setQuietPeriod(0);
        FreeStyleProject project = r.createFreeStyleProject();
        project.setAssignedLabel(Label.get(LABEL));
        project.scheduleBuild2(0);

        // Only what Jenkins does by itself: the queue is maintained, which makes the build buildable.
        Queue.getInstance().maintain();

        waitForAtLeastAgents(3);
        assertThat(countAgents(), equalTo(3));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(3));
    }

    /**
     * The other half of the reported defect: even with the right target, waiting for the next
     * periodic pass leaves the pool a spare short for up to a minute after every build starts. A
     * build taking an executor is what has to start the replacement, so the spare is booting while
     * that build runs rather than after the build behind it has already had to wait.
     */
    @Test
    void testTakingAnExecutorStartsTheNextSpareStraightAway() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 2, null);
        SlaveTemplate template = template("only", 20);
        template.setAvoidUsingOrphanedNodes(true);
        EC2Cloud cloud = cloud(rule, template);

        MinimumInstanceChecker.checkForMinimumInstances();
        assertThat("a label nobody is using costs nothing", countAgents(), equalTo(0));

        // A build lands on the agent Jenkins raised for it and takes its executor.
        cloud.provision(template, 1);
        EC2Computer computer = onlyAgent();
        int launchedBefore = AmazonEC2FactoryMockImpl.instances.size();
        retentionStrategyOf(computer).taskAccepted(new Executor(computer, 0), null);

        /*
         * Nothing else has happened: no queued build, no periodic pass, and the clock has not moved.
         * The agent above counts as one of the two the label now wants, so one more is launched.
         */
        waitForAtLeastAgents(2);
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(2));
        assertThat(
                "one launch, not a whole pool", AmazonEC2FactoryMockImpl.instances.size(), equalTo(launchedBefore + 1));
    }

    /**
     * Hot spare passes run off the thread that triggered them, so their agents are not there the
     * instant a build is queued or an executor is taken.
     *
     * <p>Waits for the number of spares asked for, and no longer: a label serving builds ends up
     * with more agents than that, because the agents running those builds are not spares.
     */
    private static void waitForAtLeastAgents(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (countAgents() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat("the hot spare pass should have provisioned by now", countAgents(), greaterThanOrEqualTo(expected));
    }

    private static EC2Computer onlyAgent() {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no EC2 agent was provisioned"));
    }

    private static EC2RetentionStrategy retentionStrategyOf(EC2Computer computer) {
        EC2AbstractSlave node = computer.getNode();
        assertThat(node, notNullValue());
        return (EC2RetentionStrategy) node.getRetentionStrategy();
    }

    /**
     * A build fanning out to twenty parallel branches puts twenty tasks in the queue at once, and
     * that is measured demand rather than a guess. The label goes to twenty on the spot instead of
     * climbing there five at a time over four minutes with nineteen branches waiting.
     */
    @Test
    void testAWideParallelBuildWarmsTheLabelToItsWholeWidth() throws Exception {
        HotSpareConfigByLabel rule = rule(0, 5, null);
        EC2Cloud cloud = cloud(rule, template("only", 30));

        r.jenkins.setQuietPeriod(0);
        for (int branch = 0; branch < 20; branch++) {
            FreeStyleProject project = r.createFreeStyleProject();
            project.setAssignedLabel(Label.get(LABEL));
            project.scheduleBuild2(0);
        }
        Queue.getInstance().maintain();

        waitForAtLeastAgents(20);
        assertThat(countAgents(), equalTo(20));
        assertThat(HotSpareDemand.of(cloud, LABEL).getTarget(), equalTo(20));
    }

    private void removeAllAgents() throws Exception {
        for (Node node : new ArrayList<>(r.jenkins.getNodes())) {
            r.jenkins.removeNode(node);
        }
    }

    private static HotSpareConfigByLabel rule(int baseHotSpares, int scalingFactor, Integer maxHotSpares) {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(baseHotSpares);
        rule.setScalingFactor(scalingFactor);
        rule.setMaxHotSpares(maxHotSpares);
        return rule;
    }

    private static int countAgents() {
        return (int) Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .count();
    }

    private static Map<String, Long> agentsByTemplate() {
        return Arrays.stream(Jenkins.get().getComputers())
                .filter(EC2Computer.class::isInstance)
                .map(EC2Computer.class::cast)
                .map(EC2Computer::getNode)
                .filter(node -> node != null)
                .collect(Collectors.groupingBy(node -> node.templateDescription, Collectors.counting()));
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) throws Exception {
        SSHCredentialHelper.assureSshCredentialAvailableThroughCredentialProviders("ghi");
        // A cloud-wide cap high enough to leave the template caps as the only limit in play.
        EC2Cloud cloud = new EC2Cloud(
                "test-cloud", true, "abc", "us-east-1", null, "ghi", "100", List.of(templates), null, null);
        cloud.setHotSpareConfigsByLabel(List.of(rule));
        r.jenkins.clouds.add(cloud);
        return cloud;
    }

    private static final class MovableClock extends Clock {

        private long millis = TimeUnit.DAYS.toMillis(1);

        void advanceMinutes(long minutes) {
            millis += TimeUnit.MINUTES.toMillis(minutes);
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

    private static SlaveTemplate template(String description, int instanceCap) {
        return new SlaveTemplate(
                "ami-" + description,
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                InstanceType.M1_LARGE.toString(),
                false,
                LABEL,
                Node.Mode.NORMAL,
                description,
                "",
                "",
                "",
                "1",
                "",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "",
                false,
                null,
                null,
                null,
                0,
                0,
                String.valueOf(instanceCap),
                null,
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_IP,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
    }
}
