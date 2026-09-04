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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.Set;
import java.util.TreeSet;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Hot spare scaling for one Jenkins label, across every {@link SlaveTemplate} of a cloud that
 * carries that label.
 *
 * <p>Templates sharing a label are treated as interchangeable hardware, so the numbers here apply
 * to the group as a whole: {@link #getMaxHotSpares()} caps the spares held by all of those
 * templates combined, not each of them. A matching rule supersedes
 * {@link SlaveTemplate#getMinimumNumberOfSpareInstances()} for those templates, because owning the
 * spare count for the label is the point of the rule.
 *
 * <p>The idle timeout and grace period configured here also win over the template values by
 * default. Ticking the corresponding {@code allowTemplate...Override} box gives precedence back to
 * templates that set the value explicitly.
 */
public class HotSpareConfigByLabel extends AbstractDescribableImpl<HotSpareConfigByLabel> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String label;

    private int scalingFactor = 5;

    private int baseHotSpares = 0;

    private Integer maxHotSpares;

    private int idleTimeoutMinutes = 15;

    private int gracePeriodMinutes = 0;

    private boolean discardAfterGracePeriod = true;

    private boolean allowTemplateIdleTimeoutOverride = false;

    private boolean allowTemplateGracePeriodOverride = false;

    @DataBoundConstructor
    public HotSpareConfigByLabel(String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    public String getLabel() {
        return label;
    }

    /**
     * @return how many spares to hold per queued build for this label.
     */
    public int getScalingFactor() {
        return scalingFactor;
    }

    @DataBoundSetter
    public void setScalingFactor(int scalingFactor) {
        this.scalingFactor = Math.max(0, scalingFactor);
    }

    /**
     * @return the number of spares to keep warm for this label even when nothing is queued.
     */
    public int getBaseHotSpares() {
        return baseHotSpares;
    }

    @DataBoundSetter
    public void setBaseHotSpares(int baseHotSpares) {
        this.baseHotSpares = Math.max(0, baseHotSpares);
    }

    /**
     * @return the ceiling on spares held by the whole label group, or {@code null} for no ceiling.
     */
    @CheckForNull
    public Integer getMaxHotSpares() {
        return maxHotSpares;
    }

    @DataBoundSetter
    public void setMaxHotSpares(Integer maxHotSpares) {
        this.maxHotSpares = maxHotSpares == null ? null : Math.max(0, maxHotSpares);
    }

    /**
     * @return idle termination for agents of this label, in minutes. 0 means never.
     */
    public int getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    @DataBoundSetter
    public void setIdleTimeoutMinutes(int idleTimeoutMinutes) {
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /**
     * @return minutes an agent of this label is given to come online, or 0 for no grace period.
     */
    public int getGracePeriodMinutes() {
        return gracePeriodMinutes;
    }

    @DataBoundSetter
    public void setGracePeriodMinutes(int gracePeriodMinutes) {
        this.gracePeriodMinutes = Math.max(0, gracePeriodMinutes);
    }

    public boolean isDiscardAfterGracePeriod() {
        return discardAfterGracePeriod;
    }

    @DataBoundSetter
    public void setDiscardAfterGracePeriod(boolean discardAfterGracePeriod) {
        this.discardAfterGracePeriod = discardAfterGracePeriod;
    }

    /**
     * @return whether a template that sets its own idle termination time takes precedence over this
     *     rule. A template that leaves it blank still follows the rule.
     */
    public boolean isAllowTemplateIdleTimeoutOverride() {
        return allowTemplateIdleTimeoutOverride;
    }

    @DataBoundSetter
    public void setAllowTemplateIdleTimeoutOverride(boolean allowTemplateIdleTimeoutOverride) {
        this.allowTemplateIdleTimeoutOverride = allowTemplateIdleTimeoutOverride;
    }

    /**
     * @return whether a template that sets its own grace period takes precedence over this rule. A
     *     template that leaves it at 0 still follows the rule.
     */
    public boolean isAllowTemplateGracePeriodOverride() {
        return allowTemplateGracePeriodOverride;
    }

    @DataBoundSetter
    public void setAllowTemplateGracePeriodOverride(boolean allowTemplateGracePeriodOverride) {
        this.allowTemplateGracePeriodOverride = allowTemplateGracePeriodOverride;
    }

    /**
     * @return whether this rule governs a template, i.e. whether the template carries the rule's
     *     label.
     */
    boolean matches(SlaveTemplate template) {
        if (label == null || template == null) {
            return false;
        }
        // The same test EC2Cloud#getTemplates(Label) uses, so a rule governs exactly the templates
        // that label would provision from.
        return Label.get(label).matches(template.getLabelSet());
    }

    @Extension
    @Symbol("hotSpareConfigByLabel")
    public static class DescriptorImpl extends Descriptor<HotSpareConfigByLabel> {

        @Override
        public String getDisplayName() {
            return "Hot Spare Rule";
        }

        /**
         * Offers the labels this cloud's templates actually carry. The current value is always
         * included so a label that has since been removed from every template still round-trips
         * instead of being silently cleared on save.
         */
        public ListBoxModel doFillLabelItems(@AncestorInPath EC2Cloud cloud, @QueryParameter String label) {
            ListBoxModel items = new ListBoxModel();
            Set<String> labels = new TreeSet<>();
            if (cloud != null) {
                for (SlaveTemplate template : cloud.getTemplates()) {
                    for (LabelAtom atom : template.getLabelSet()) {
                        labels.add(atom.getName());
                    }
                }
            }
            String current = Util.fixEmptyAndTrim(label);
            if (current != null) {
                labels.add(current);
            }
            for (String name : labels) {
                items.add(name, name);
            }
            return items;
        }

        @POST
        public FormValidation doCheckLabel(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("A label is required");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckScalingFactor(@QueryParameter String value) {
            return nonNegative(value, "Scaling factor");
        }

        @POST
        public FormValidation doCheckBaseHotSpares(@QueryParameter String value) {
            return nonNegative(value, "Base hot spares");
        }

        @POST
        public FormValidation doCheckMaxHotSpares(@QueryParameter String value, @QueryParameter String baseHotSpares) {
            FormValidation nonNegative = nonNegative(value, "Maximum hot spares");
            if (nonNegative.kind != FormValidation.Kind.OK || Util.fixEmptyAndTrim(value) == null) {
                return nonNegative;
            }
            try {
                int max = Integer.parseInt(value.trim());
                int base = Integer.parseInt(Util.fixEmptyAndTrim(baseHotSpares) == null ? "0" : baseHotSpares.trim());
                if (max < base) {
                    return FormValidation.error(
                            "Maximum hot spares must not be lower than the %d base hot spares", base);
                }
            } catch (NumberFormatException ignore) {
                // The individual field validators report the malformed value.
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckGracePeriodMinutes(@QueryParameter String value) {
            return nonNegative(value, "Grace period");
        }

        @POST
        public FormValidation doCheckIdleTimeoutMinutes(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok();
            }
            try {
                Integer.parseInt(value.trim());
                return FormValidation.ok();
            } catch (NumberFormatException ignore) {
                return FormValidation.error("Idle timeout must be an integer");
            }
        }

        private static FormValidation nonNegative(String value, String what) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok();
            }
            try {
                if (Integer.parseInt(value.trim()) >= 0) {
                    return FormValidation.ok();
                }
            } catch (NumberFormatException ignore) {
                // Fall through to the error below.
            }
            return FormValidation.error("%s must be a non-negative integer (or null)", what);
        }
    }
}
