package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ses.SesConfigurationSetService;
import io.github.hectorvent.floci.services.ses.SesDedicatedIpService;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDestination;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DashboardOptions;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EventBridgeDestination;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.GuardianOptions;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.KinesisFirehoseDestination;
import io.github.hectorvent.floci.services.ses.model.SnsDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for SES: {@code AWS::SES::ConfigurationSet},
 * {@code AWS::SES::ConfigurationSetEventDestination}, {@code AWS::SES::EmailIdentity} and
 * {@code AWS::SES::DedicatedIpPool}. These are SESv2 resources, written to the same stores the v1
 * Query API and the v2 REST API read, so a configuration set created here takes v1 and v2 event
 * destinations alike.
 *
 * <p>SES is one AWS service split across domain services in Floci. The facade supplies what crosses
 * domains (the create-time probes and the tenant, certificate and policy guards around deletes); the
 * domain services carry the per-option updates the facade does not expose.
 *
 * <p>{@code Ref} is the configuration set name, the event destination name, the identity and the pool
 * name. The names are createOnly, so a rename is refused as an unsupported replacement.
 */
@ApplicationScoped
public class SesCfnProvisioner implements CfnResourceProvisioner {

    private static final String CONFIGURATION_SET = "AWS::SES::ConfigurationSet";
    private static final String EVENT_DESTINATION = "AWS::SES::ConfigurationSetEventDestination";
    private static final String EMAIL_IDENTITY = "AWS::SES::EmailIdentity";
    private static final String DEDICATED_IP_POOL = "AWS::SES::DedicatedIpPool";

    /** Where the event destination's owning set is kept, since the physical id is only its name. */
    private static final String CONFIGURATION_SET_NAME_ATTR = "__FlociSesConfigurationSetName";
    private static final int NAME_MAX = 64;

    private final SesService sesService;
    private final SesConfigurationSetService configSetService;
    private final SesIdentityService identityService;
    private final SesDedicatedIpService dedicatedIpService;

    @Inject
    public SesCfnProvisioner(SesService sesService, SesConfigurationSetService configSetService,
                             SesIdentityService identityService, SesDedicatedIpService dedicatedIpService) {
        this.sesService = sesService;
        this.configSetService = configSetService;
        this.identityService = identityService;
        this.dedicatedIpService = dedicatedIpService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CONFIGURATION_SET, EVENT_DESTINATION, EMAIL_IDENTITY, DEDICATED_IP_POOL);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case CONFIGURATION_SET -> provisionConfigurationSet(r, props, ctx);
            case EVENT_DESTINATION -> provisionEventDestination(r, props, ctx);
            case EMAIL_IDENTITY -> provisionEmailIdentity(r, props, ctx);
            case DEDICATED_IP_POOL -> provisionDedicatedIpPool(r, props, ctx);
            default -> throw new IllegalStateException("SesCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    // ──────────────────────────── ConfigurationSet ────────────────────────────

    private void provisionConfigurationSet(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"), r.getLogicalId(), NAME_MAX, false);
        String region = ctx.region();
        JsonNode delivery = resolved(props, "DeliveryOptions", ctx);
        JsonNode tracking = resolved(props, "TrackingOptions", ctx);
        JsonNode suppression = resolved(props, "SuppressionOptions", ctx);
        JsonNode vdm = resolved(props, "VdmOptions", ctx);
        JsonNode archiving = resolved(props, "ArchivingOptions", ctx);
        // Null leaves the flag unset, which SES reads as its default (both on).
        Boolean sendingEnabled = optionalBool(resolved(props, "SendingOptions", ctx).path("SendingEnabled"));
        Boolean reputationEnabled =
                optionalBool(resolved(props, "ReputationOptions", ctx).path("ReputationMetricsEnabled"));
        List<Tag> tags = tags(props, ctx);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(name)) {
                throw replacementNotSupported("Name");
            }
            if (configSetService.find(name, region).isPresent()) {
                sesService.setConfigurationSetTrackingOptions(name, trackingOptions(tracking), region);
                sesService.setConfigurationSetDeliveryOptions(name, deliveryOptions(delivery), region);
                configSetService.setVdmOptions(name, vdmOptions(vdm), region);
                configSetService.setArchivingOptions(name, archivingOptions(archiving), region);
                List<String> reasons = suppressedReasons(suppression);
                if (reasons != null) {
                    configSetService.putSuppressionOptions(name, reasons, region);
                }
                // What the template dropped goes back to unset, so it reads as SES's default again.
                ConfigurationSet cs = configSetService.get(name, region);
                cs.setSendingEnabled(sendingEnabled);
                cs.setReputationMetricsEnabled(reputationEnabled);
                if (reasons == null) {
                    cs.setSuppressionOptions(null);
                }
                configSetService.save(cs, region);
                reconcileConfigurationSetTags(name, tags, region);
                r.setPhysicalId(name);
                return;
            }
        }

        ConfigurationSet cs = new ConfigurationSet(name);
        cs.setTags(tags);
        cs.setTrackingOptions(trackingOptions(tracking));
        cs.setDeliveryOptions(deliveryOptions(delivery));
        cs.setVdmOptions(vdmOptions(vdm));
        cs.setArchivingOptions(archivingOptions(archiving));
        cs.setSendingEnabled(sendingEnabled);
        cs.setReputationMetricsEnabled(reputationEnabled);
        List<String> reasons = suppressedReasons(suppression);
        if (reasons != null) {
            SuppressionOptions options = new SuppressionOptions();
            options.setSuppressedReasons(reasons);
            cs.setSuppressionOptions(options);
        }
        sesService.createConfigurationSet(cs, region);
        r.setPhysicalId(name);
    }

    private void reconcileConfigurationSetTags(String name, List<Tag> desired, String region) {
        List<String> stale = staleTagKeys(configSetService.listTags(name, region), desired);
        if (!stale.isEmpty()) {
            configSetService.untag(name, region, stale);
        }
        if (!desired.isEmpty()) {
            configSetService.tag(name, region, desired);
        }
    }

    private static TrackingOptions trackingOptions(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        TrackingOptions options = new TrackingOptions();
        options.setCustomRedirectDomain(text(node, "CustomRedirectDomain"));
        options.setHttpsPolicy(text(node, "HttpsPolicy"));
        return options;
    }

    private static DeliveryOptions deliveryOptions(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        DeliveryOptions options = new DeliveryOptions();
        options.setTlsPolicy(text(node, "TlsPolicy"));
        options.setSendingPoolName(text(node, "SendingPoolName"));
        String maxDelivery = text(node, "MaxDeliverySeconds");
        if (maxDelivery != null) {
            options.setMaxDeliverySeconds(parseLong("DeliveryOptions.MaxDeliverySeconds", maxDelivery));
        }
        return options;
    }

    private static VdmOptions vdmOptions(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        VdmOptions options = new VdmOptions();
        String engagement = text(node.path("DashboardOptions"), "EngagementMetrics");
        if (engagement != null) {
            DashboardOptions dashboard = new DashboardOptions();
            dashboard.setEngagementMetrics(engagement);
            options.setDashboardOptions(dashboard);
        }
        String sharedDelivery = text(node.path("GuardianOptions"), "OptimizedSharedDelivery");
        if (sharedDelivery != null) {
            GuardianOptions guardian = new GuardianOptions();
            guardian.setOptimizedSharedDelivery(sharedDelivery);
            options.setGuardianOptions(guardian);
        }
        return options;
    }

    private static ArchivingOptions archivingOptions(JsonNode node) {
        String archiveArn = text(node, "ArchiveArn");
        if (archiveArn == null) {
            return null;
        }
        ArchivingOptions options = new ArchivingOptions();
        options.setArchiveArn(archiveArn);
        return options;
    }

    /** Null when the template gives no suppression options, which leaves the account default in force. */
    private static List<String> suppressedReasons(JsonNode node) {
        if (node.isMissingNode()) {
            return null;
        }
        return strings(node.path("SuppressedReasons"));
    }

    // ──────────────────────── ConfigurationSetEventDestination ────────────────────────

    private void provisionEventDestination(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String configSetName = ctx.resolveOptional(props, "ConfigurationSetName");
        if (configSetName == null || configSetName.isBlank()) {
            throw new AwsException("ValidationError", "ConfigurationSetName is required.", 400);
        }
        JsonNode node = resolved(props, "EventDestination", ctx);
        if (node.isMissingNode()) {
            throw new AwsException("ValidationError", "EventDestination is required.", 400);
        }
        String priorName = ctx.isUpdate() ? ctx.priorPhysicalId() : null;
        String priorSet = r.getAttributes().get(CONFIGURATION_SET_NAME_ATTR);
        String name = text(node, "Name");
        if (name == null) {
            name = priorName != null ? priorName : ctx.generatePhysicalName(r.getLogicalId(), NAME_MAX, false);
        }
        EventDestination destination = eventDestination(node);

        if (ctx.isUpdate() && priorSet != null && !priorSet.equals(configSetName)) {
            throw replacementNotSupported("ConfigurationSetName");
        }
        boolean exists = ctx.isUpdate() && hasEventDestination(configSetName, priorName, region);
        if (exists && priorName.equals(name)) {
            configSetService.updateEventDestination(configSetName, name, destination, ctx.accountId(), region);
        } else {
            configSetService.createEventDestination(configSetName, name, destination, ctx.accountId(), region);
            if (exists) {
                // A renamed destination is a new entry on the same set; the old one goes with the update.
                configSetService.deleteEventDestination(configSetName, priorName, region);
            }
        }
        r.setPhysicalId(name);
        r.getAttributes().put("Id", name);
        r.getAttributes().put(CONFIGURATION_SET_NAME_ATTR, configSetName);
    }

    private boolean hasEventDestination(String configSetName, String name, String region) {
        return configSetService.find(configSetName, region)
                .map(cs -> cs.getEventDestinations().stream().anyMatch(d -> name.equals(d.getName())))
                .orElse(false);
    }

    private static EventDestination eventDestination(JsonNode node) {
        EventDestination destination = new EventDestination();
        destination.setEnabled(bool(node.path("Enabled"), false));
        destination.setMatchingEventTypes(strings(node.path("MatchingEventTypes")).stream()
                .map(SesCfnProvisioner::eventType)
                .toList());
        JsonNode sns = node.path("SnsDestination");
        if (!sns.isMissingNode()) {
            SnsDestination dest = new SnsDestination();
            dest.setTopicArn(text(sns, "TopicARN"));
            destination.setSnsDestination(dest);
        }
        JsonNode firehose = node.path("KinesisFirehoseDestination");
        if (!firehose.isMissingNode()) {
            KinesisFirehoseDestination dest = new KinesisFirehoseDestination();
            dest.setIamRoleArn(text(firehose, "IAMRoleARN"));
            dest.setDeliveryStreamArn(text(firehose, "DeliveryStreamARN"));
            destination.setKinesisFirehoseDestination(dest);
        }
        JsonNode eventBridge = node.path("EventBridgeDestination");
        if (!eventBridge.isMissingNode()) {
            EventBridgeDestination dest = new EventBridgeDestination();
            dest.setEventBusArn(text(eventBridge, "EventBusArn"));
            destination.setEventBridgeDestination(dest);
        }
        JsonNode cloudWatch = node.path("CloudWatchDestination");
        if (!cloudWatch.isMissingNode()) {
            List<CloudWatchDimensionConfiguration> dimensions = new ArrayList<>();
            for (JsonNode dim : cloudWatch.path("DimensionConfigurations")) {
                CloudWatchDimensionConfiguration config = new CloudWatchDimensionConfiguration();
                config.setDimensionName(text(dim, "DimensionName"));
                config.setDimensionValueSource(text(dim, "DimensionValueSource"));
                config.setDefaultDimensionValue(text(dim, "DefaultDimensionValue"));
                dimensions.add(config);
            }
            CloudWatchDestination dest = new CloudWatchDestination();
            dest.setDimensionConfigurations(dimensions);
            destination.setCloudWatchDestination(dest);
        }
        return destination;
    }

    /** The schema documents the v1 spelling ({@code renderingFailure}); Floci stores the v2 enum. */
    private static String eventType(String value) {
        return value.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    // ──────────────────────────── EmailIdentity ────────────────────────────

    private void provisionEmailIdentity(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String emailIdentity = ctx.resolveOptional(props, "EmailIdentity");
        if (emailIdentity == null || emailIdentity.isBlank()) {
            throw new AwsException("ValidationError", "EmailIdentity is required.", 400);
        }
        String configSetName = text(resolved(props, "ConfigurationSetAttributes", ctx), "ConfigurationSetName");
        List<Tag> tags = tags(props, ctx);
        boolean domain = !emailIdentity.contains("@");

        boolean existing = false;
        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(emailIdentity)) {
                throw replacementNotSupported("EmailIdentity");
            }
            existing = identityService.find(emailIdentity, region).isPresent();
        }
        if (existing) {
            sesService.setEmailIdentityConfigurationSet(emailIdentity, configSetName, region);
            reconcileIdentityTags(emailIdentity, tags, region);
        } else {
            sesService.createEmailIdentity(emailIdentity, configSetName, tags, region);
        }

        JsonNode signing = resolved(props, "DkimSigningAttributes", ctx);
        if (!signing.isMissingNode()) {
            String selector = text(signing, "DomainSigningSelector");
            String privateKey = text(signing, "DomainSigningPrivateKey");
            String origin = selector != null || privateKey != null ? "EXTERNAL" : "AWS_SES";
            if ("EXTERNAL".equals(origin) && (selector == null || privateKey == null)) {
                throw new AwsException("ValidationError",
                        "DkimSigningAttributes requires both DomainSigningSelector and DomainSigningPrivateKey.", 400);
            }
            identityService.putDkimSigningAttributes(emailIdentity, origin, selector,
                    text(signing, "NextSigningKeyLength"), region);
        }
        // An absent attribute block is SES's default, so an update that drops one puts it back.
        JsonNode dkim = resolved(props, "DkimAttributes", ctx);
        if (!dkim.isMissingNode() || (existing && domain)) {
            identityService.setDkimAttributes(emailIdentity, bool(dkim.path("SigningEnabled"), true), region);
        }
        JsonNode feedback = resolved(props, "FeedbackAttributes", ctx);
        if (!feedback.isMissingNode() || existing) {
            identityService.setFeedbackForwardingEnabled(emailIdentity,
                    bool(feedback.path("EmailForwardingEnabled"), true), region);
        }
        JsonNode mailFrom = resolved(props, "MailFromAttributes", ctx);
        String mailFromDomain = text(mailFrom, "MailFromDomain");
        if (mailFromDomain != null || existing) {
            identityService.setMailFromDomain(emailIdentity, mailFromDomain == null ? "" : mailFromDomain,
                    mxFailureBehavior(text(mailFrom, "BehaviorOnMxFailure")), region);
        }

        Identity identity = identityService.find(emailIdentity, region).orElseThrow();
        Identity dkimSource = identityService.effectiveDkimSource(identity, region);
        List<String> tokens = dkimSource == null || dkimSource.getDkimTokens() == null
                ? List.of() : dkimSource.getDkimTokens();
        String dkimDomain = domain ? emailIdentity : emailIdentity.substring(emailIdentity.indexOf('@') + 1);
        r.setPhysicalId(emailIdentity);
        r.getAttributes().put("DkimDNSTokenName1", dkimName(tokens, 0, dkimDomain));
        r.getAttributes().put("DkimDNSTokenName2", dkimName(tokens, 1, dkimDomain));
        r.getAttributes().put("DkimDNSTokenName3", dkimName(tokens, 2, dkimDomain));
        r.getAttributes().put("DkimDNSTokenValue1", dkimValue(tokens, 0));
        r.getAttributes().put("DkimDNSTokenValue2", dkimValue(tokens, 1));
        r.getAttributes().put("DkimDNSTokenValue3", dkimValue(tokens, 2));
    }

    private void reconcileIdentityTags(String identity, List<Tag> desired, String region) {
        List<String> stale = staleTagKeys(identityService.listTags(identity, region), desired);
        if (!stale.isEmpty()) {
            identityService.untag(identity, region, stale);
        }
        if (!desired.isEmpty()) {
            identityService.tag(identity, region, desired);
        }
    }

    /** The CNAME name SES publishes for an Easy DKIM token, or blank when there is no such token. */
    private static String dkimName(List<String> tokens, int index, String domain) {
        return index < tokens.size() ? tokens.get(index) + "._domainkey." + domain : "";
    }

    private static String dkimValue(List<String> tokens, int index) {
        return index < tokens.size() ? tokens.get(index) + ".dkim.amazonses.com" : "";
    }

    /** The schema uses the v2 enum; the identity store keeps the v1 spelling. */
    private static String mxFailureBehavior(String v2) {
        if (v2 == null) {
            return null;
        }
        return switch (v2) {
            case "USE_DEFAULT_VALUE" -> "UseDefaultValue";
            case "REJECT_MESSAGE" -> "RejectMessage";
            default -> v2;
        };
    }

    // ──────────────────────────── DedicatedIpPool ────────────────────────────

    private void provisionDedicatedIpPool(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "PoolName"), r.getLogicalId(), NAME_MAX, true);
        String scalingMode = blankToNull(ctx.resolveOptional(props, "ScalingMode"));
        List<Tag> tags = tags(props, ctx);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(name)) {
                throw replacementNotSupported("PoolName");
            }
            if (dedicatedIpService.dedicatedIpPoolExists(name, region)) {
                DedicatedIpPool pool = dedicatedIpService.getDedicatedIpPool(name, region);
                String desired = scalingMode == null ? "STANDARD" : scalingMode;
                if (!Objects.equals(pool.getScalingMode(), desired)) {
                    dedicatedIpService.putDedicatedIpPoolScalingAttributes(name, desired, region);
                }
                List<String> stale = staleTagKeys(dedicatedIpService.listTags(name, region), tags);
                if (!stale.isEmpty()) {
                    dedicatedIpService.untag(name, region, stale);
                }
                if (!tags.isEmpty()) {
                    dedicatedIpService.tag(name, region, tags);
                }
                r.setPhysicalId(name);
                return;
            }
        }
        dedicatedIpService.createDedicatedIpPool(name, scalingMode, tags, region);
        r.setPhysicalId(name);
    }

    // ──────────────────────────── Delete ────────────────────────────

    @Override
    public void delete(StackResource resource, String region) {
        if (EVENT_DESTINATION.equals(resource.getResourceType())) {
            String physicalId = resource.getPhysicalId();
            String configSetName = resource.getAttributes() == null
                    ? null : resource.getAttributes().get(CONFIGURATION_SET_NAME_ATTR);
            if (physicalId == null || physicalId.isBlank() || configSetName == null) {
                return;
            }
            // Deleting the set takes its destinations with it, so a missing set means already gone.
            CfnDeletes.safeDelete("SES event destination", physicalId,
                    () -> configSetService.deleteEventDestination(configSetName, physicalId, region),
                    "NotFoundException", "ConfigurationSetDoesNotExist");
            return;
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case CONFIGURATION_SET -> CfnDeletes.safeDelete("SES configuration set", physicalId,
                    () -> sesService.deleteConfigurationSet(physicalId, region), "ConfigurationSetDoesNotExist");
            case EMAIL_IDENTITY -> CfnDeletes.safeDelete("SES email identity", physicalId,
                    () -> sesService.deleteIdentity(physicalId, region), "NotFoundException");
            case DEDICATED_IP_POOL -> CfnDeletes.safeDelete("SES dedicated IP pool", physicalId,
                    () -> dedicatedIpService.deleteDedicatedIpPool(physicalId, region), "NotFoundException");
            default -> {
                // An event destination without its set cannot be addressed; see delete(StackResource, String).
            }
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** The property with every intrinsic resolved, or a missing node when absent or null. */
    private static JsonNode resolved(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.hasNonNull(name)) {
            return MissingNode.getInstance();
        }
        JsonNode node = ctx.engine().resolveNode(props.get(name));
        return node == null || node.isNull()
                ? MissingNode.getInstance() : node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** A boolean given literally or as the string a parameter resolves to. */
    private static boolean bool(JsonNode node, boolean defaultValue) {
        if (node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        String text = node.asText().trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return defaultValue;
    }

    private static Boolean optionalBool(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return bool(node, false);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static long parseLong(String property, String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", property + " must be a number: " + value, 400);
        }
    }

    private static List<Tag> tags(JsonNode props, ProvisionContext ctx) {
        List<Tag> tags = new ArrayList<>();
        for (Map.Entry<String, String> entry : ctx.resolveTags(props, "Tags").entrySet()) {
            tags.add(new Tag(entry.getKey(), entry.getValue()));
        }
        return tags;
    }

    private static List<String> staleTagKeys(List<Tag> current, List<Tag> desired) {
        Set<String> keep = new HashSet<>();
        desired.forEach(t -> keep.add(t.key()));
        return current.stream().map(Tag::key).filter(k -> !keep.contains(k)).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static AwsException replacementNotSupported(String property) {
        return new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }
}
