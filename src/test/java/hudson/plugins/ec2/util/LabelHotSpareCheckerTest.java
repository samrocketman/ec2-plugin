package hudson.plugins.ec2.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import hudson.model.Node;
import hudson.plugins.ec2.ConnectionStrategy;
import hudson.plugins.ec2.EC2AbstractSlave;
import hudson.plugins.ec2.EC2Cloud;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EbsEncryptRootVolume;
import hudson.plugins.ec2.HotSpareConfigByLabel;
import hudson.plugins.ec2.SlaveTemplate;
import hudson.plugins.ec2.Tenancy;
import java.security.Security;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Hot spare scaling driven by a label rule rather than by a single template.
 */
@WithJenkins
class LabelHotSpareCheckerTest {

    private static final String LABEL = "linux";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        // The mock client and its instance list are static, so a fresh one keeps the instances of
        // one test from counting against the caps of the next.
        AmazonEC2FactoryMockImpl.mock = AmazonEC2FactoryMockImpl.createAmazonEC2Mock();
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

    @Test
    void testDesiredCountUsesTheBaseTheScalingFactorAndTheCeiling() {
        HotSpareConfigByLabel rule = rule(2, 3, null);

        assertThat(
                "the base applies with an empty queue", MinimumInstanceChecker.desiredHotSpares(rule, 0), equalTo(2));
        assertThat(
                "scaling wins once it exceeds the base", MinimumInstanceChecker.desiredHotSpares(rule, 2), equalTo(6));

        rule.setMaxHotSpares(4);
        assertThat("the ceiling clamps the scaled value", MinimumInstanceChecker.desiredHotSpares(rule, 2), equalTo(4));

        rule.setMaxHotSpares(1);
        assertThat("the ceiling also clamps the base", MinimumInstanceChecker.desiredHotSpares(rule, 0), equalTo(1));
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
        EC2Cloud cloud =
                new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(templates), null, null);
        cloud.setHotSpareConfigsByLabel(List.of(rule));
        r.jenkins.clouds.add(cloud);
        return cloud;
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
