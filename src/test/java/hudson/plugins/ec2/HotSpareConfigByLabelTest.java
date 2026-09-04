package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import hudson.model.Node;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Precedence between a label hot spare rule and the template it governs. The rule wins by default;
 * a template only takes over when the rule opts in and the template sets the value explicitly.
 */
@WithJenkins
class HotSpareConfigByLabelTest {

    private static final String LABEL = "ttt";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testRuleMatchesTemplatesCarryingItsLabel() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        EC2Cloud cloud = cloud(rule, template("30", 0));

        assertNotNull(cloud.getHotSpareConfigFor(computer(cloud, template("30", 0))));
        assertNull(
                cloud.getHotSpareConfigFor(computer(cloud, templateWithLabels("30", 0, "unrelated"))),
                "a rule must not govern a template that does not carry its label");
    }

    @Test
    void testLabelRuleIdleTimeoutWinsOverTemplateByDefault() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 0))), equalTo(45));
    }

    @Test
    void testTemplateIdleTimeoutWinsWhenOverrideIsAllowedAndSet() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(
                "null keeps the retention strategy value, which came from the template",
                cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 0))),
                nullValue());
    }

    /**
     * The regression guard against the override nullifying the rule: an unset template value must
     * not beat the rule, or every default template would silently win.
     */
    @Test
    void testUnsetTemplateIdleTimeoutFallsBackToRuleEvenWithOverrideAllowed() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template(null, 0))), equalTo(45));
        assertThat(
                "a blank string is unset, not zero",
                cloud.resolveIdleTerminationMinutes(computer(cloud, template("  ", 0))),
                equalTo(45));
    }

    @Test
    void testNoRuleLeavesTheTemplateValueInPlace() throws Exception {
        EC2Cloud cloud = cloud(null);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 7))), nullValue());
        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(7));
    }

    @Test
    void testLabelRuleGracePeriodWinsOverTemplateByDefault() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setGracePeriodMinutes(20);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(20));
    }

    @Test
    void testTemplateGracePeriodWinsWhenOverrideIsAllowedAndSet() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setGracePeriodMinutes(20);
        rule.setAllowTemplateGracePeriodOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(7));
        assertThat(
                "a template grace period of 0 is unset, so the rule still applies",
                cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 0))),
                equalTo(20));
    }

    /**
     * The discard flag has no precedence rule of its own: it comes from whichever grace period was
     * selected, so a grace period and its behaviour are never mixed from two sources.
     */
    @Test
    void testDiscardFlagFollowsTheWinningGracePeriod() throws Exception {
        HotSpareConfigByLabel labelWins = new HotSpareConfigByLabel(LABEL);
        labelWins.setGracePeriodMinutes(20);
        labelWins.setDiscardAfterGracePeriod(false);
        EC2Cloud cloudWhereLabelWins = cloud(labelWins);
        SlaveTemplate discardingTemplate = template("30", 7);
        discardingTemplate.setDiscardAfterGracePeriod(true);

        assertThat(
                "the label rule supplied the grace period, so it supplies the flag too",
                cloudWhereLabelWins.resolveDiscardAfterGracePeriod(computer(cloudWhereLabelWins, discardingTemplate)),
                equalTo(false));

        HotSpareConfigByLabel templateWins = new HotSpareConfigByLabel(LABEL);
        templateWins.setGracePeriodMinutes(20);
        templateWins.setDiscardAfterGracePeriod(true);
        templateWins.setAllowTemplateGracePeriodOverride(true);
        EC2Cloud cloudWhereTemplateWins = cloud(templateWins);
        SlaveTemplate keepingTemplate = template("30", 7);
        keepingTemplate.setDiscardAfterGracePeriod(false);

        assertThat(
                "the template supplied the grace period, so it supplies the flag too",
                cloudWhereTemplateWins.resolveDiscardAfterGracePeriod(
                        computer(cloudWhereTemplateWins, keepingTemplate)),
                equalTo(false));
    }

    @Test
    void testIdleTerminationForLabelLooksUpByLabelName() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.getIdleTerminationForLabel(LABEL), equalTo(45));
        assertThat(cloud.getIdleTerminationForLabel("other"), nullValue());
    }

    /**
     * Renders and submits the cloud configuration page, which is the only way to catch a field of
     * the rule that the form does not bind, or a Jelly view that does not render at all.
     */
    @Test
    void testRuleSurvivesAConfigurationFormRoundtrip() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(2);
        rule.setScalingFactor(3);
        rule.setMaxHotSpares(6);
        rule.setIdleTimeoutMinutes(20);
        rule.setGracePeriodMinutes(7);
        rule.setDiscardAfterGracePeriod(false);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        rule.setAllowTemplateGracePeriodOverride(true);

        SlaveTemplate template = template("30", 7);
        template.setHotSpareWeight(3);

        EC2Cloud cloud = cloud(rule, template);
        cloud.setRoundRobinTemplatesByLabel(true);
        r.jenkins.clouds.add(cloud);

        r.submit(r.createWebClient().goTo(cloud.getUrl() + "configure").getFormByName("config"));

        EC2Cloud reloaded = r.jenkins.clouds.get(EC2Cloud.class);
        assertThat(reloaded.isRoundRobinTemplatesByLabel(), equalTo(true));
        assertThat(reloaded.getHotSpareConfigsByLabel().size(), equalTo(1));

        HotSpareConfigByLabel reloadedRule =
                reloaded.getHotSpareConfigsByLabel().get(0);
        assertThat(reloadedRule.getLabel(), equalTo(LABEL));
        assertThat(reloadedRule.getBaseHotSpares(), equalTo(2));
        assertThat(reloadedRule.getScalingFactor(), equalTo(3));
        assertThat(reloadedRule.getMaxHotSpares(), equalTo(6));
        assertThat(reloadedRule.getIdleTimeoutMinutes(), equalTo(20));
        assertThat(reloadedRule.getGracePeriodMinutes(), equalTo(7));
        assertThat(reloadedRule.isDiscardAfterGracePeriod(), equalTo(false));
        assertThat(reloadedRule.isAllowTemplateIdleTimeoutOverride(), equalTo(true));
        assertThat(reloadedRule.isAllowTemplateGracePeriodOverride(), equalTo(true));

        SlaveTemplate reloadedTemplate = reloaded.getTemplates().get(0);
        assertThat(reloadedTemplate.getHotSpareWeight(), equalTo(3));
        assertThat(reloadedTemplate.getGracePeriodMinutes(), equalTo(7));
    }

    /**
     * The rule's label drop-down offers the labels this cloud's templates carry, and keeps the
     * configured value even after no template carries it any more.
     */
    @Test
    void testLabelItemsComeFromThisCloudsTemplates() {
        EC2Cloud cloud = cloud(null, template("30", 0), templateWithLabels("30", 0, "windows"));
        HotSpareConfigByLabel.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(HotSpareConfigByLabel.DescriptorImpl.class);

        assertThat(
                descriptor.doFillLabelItems(cloud, null).stream()
                        .map(item -> item.value)
                        .collect(Collectors.toList()),
                equalTo(List.of(LABEL, "windows")));
        assertThat(
                "a label no template carries any more must still round-trip",
                descriptor.doFillLabelItems(cloud, "retired").stream()
                        .map(item -> item.value)
                        .collect(Collectors.toList()),
                equalTo(List.of("retired", LABEL, "windows")));
        assertThat(
                "no cloud in scope must not fail the form",
                descriptor.doFillLabelItems(null, LABEL).stream()
                        .map(item -> item.value)
                        .collect(Collectors.toList()),
                equalTo(List.of(LABEL)));
    }

    @Test
    void testHotSpareConfigsDefaultToEmptyRatherThanNull() throws Exception {
        EC2Cloud cloud = cloud(null);
        assertThat(cloud.getHotSpareConfigsByLabel().size(), equalTo(0));

        cloud.setHotSpareConfigsByLabel(null);
        assertThat(cloud.getHotSpareConfigsByLabel().size(), equalTo(0));
    }

    private MockEC2Computer computer(EC2Cloud cloud, SlaveTemplate template) throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("-precedence");
        computer.setSlaveTemplate(template);
        return computer;
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) {
        EC2Cloud cloud =
                new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(templates), null, null);
        if (rule != null) {
            cloud.setHotSpareConfigsByLabel(List.of(rule));
        }
        return cloud;
    }

    private static SlaveTemplate template(String idleTerminationMinutes, int gracePeriodMinutes) {
        return templateWithLabels(idleTerminationMinutes, gracePeriodMinutes, LABEL);
    }

    private static SlaveTemplate templateWithLabels(
            String idleTerminationMinutes, int gracePeriodMinutes, String labels) {
        SlaveTemplate template = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                InstanceType.M1_LARGE.toString(),
                false,
                labels,
                Node.Mode.NORMAL,
                "AMI description",
                "",
                "",
                "",
                "1",
                "",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "",
                false,
                "subnet-123",
                null,
                idleTerminationMinutes,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
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
        template.setGracePeriodMinutes(gracePeriodMinutes);
        return template;
    }
}
