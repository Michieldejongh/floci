package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ses.SesConfigurationSetService;
import io.github.hectorvent.floci.services.ses.SesDedicatedIpService;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The SES CFN provisioner in isolation, against mocked SES services. */
class SesCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:ses-events";

    private final SesService ses = mock(SesService.class);
    private final SesConfigurationSetService configSets = mock(SesConfigurationSetService.class);
    private final SesIdentityService identities = mock(SesIdentityService.class);
    private final SesDedicatedIpService dedicatedIps = mock(SesDedicatedIpService.class);
    private final SesCfnProvisioner provisioner =
            new SesCfnProvisioner(ses, configSets, identities, dedicatedIps);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ──────────────────────────── ConfigurationSet ────────────────────────────

    @Test
    void configurationSetCreateCarriesEveryOptionThroughTheValidatedCreate() {
        StackResource r = resource("AWS::SES::ConfigurationSet", null);

        provisioner.provision(r, json("""
                {"Name": "cs1",
                 "DeliveryOptions": {"SendingPoolName": "pool1", "TlsPolicy": "REQUIRE", "MaxDeliverySeconds": 600},
                 "ReputationOptions": {"ReputationMetricsEnabled": "true"},
                 "SendingOptions": {"SendingEnabled": false},
                 "SuppressionOptions": {"SuppressedReasons": ["BOUNCE"]},
                 "TrackingOptions": {"CustomRedirectDomain": "track.example.com", "HttpsPolicy": "REQUIRE"},
                 "VdmOptions": {"DashboardOptions": {"EngagementMetrics": "ENABLED"},
                                "GuardianOptions": {"OptimizedSharedDelivery": "DISABLED"}},
                 "Tags": [{"Key": "env", "Value": "dev"}]}
                """), ctx(null));

        ArgumentCaptor<ConfigurationSet> captor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(ses).createConfigurationSet(captor.capture(), eq(REGION));
        ConfigurationSet cs = captor.getValue();
        assertEquals("cs1", cs.getName());
        assertEquals("pool1", cs.getDeliveryOptions().getSendingPoolName());
        assertEquals("REQUIRE", cs.getDeliveryOptions().getTlsPolicy());
        assertEquals(600L, cs.getDeliveryOptions().getMaxDeliverySeconds());
        assertTrue(cs.getReputationMetricsEnabled());
        assertFalse(cs.getSendingEnabled());
        assertEquals(List.of("BOUNCE"), cs.getSuppressionOptions().getSuppressedReasons());
        assertEquals("track.example.com", cs.getTrackingOptions().getCustomRedirectDomain());
        assertEquals("ENABLED", cs.getVdmOptions().getDashboardOptions().getEngagementMetrics());
        assertEquals("DISABLED", cs.getVdmOptions().getGuardianOptions().getOptimizedSharedDelivery());
        assertEquals(List.of(new Tag("env", "dev")), cs.getTags());
        assertEquals("cs1", r.getPhysicalId());
    }

    @Test
    void unnamedConfigurationSetGetsAGeneratedNameAndSesDefaults() {
        provisioner.provision(resource("AWS::SES::ConfigurationSet", null), json("{}"), ctx(null));

        ArgumentCaptor<ConfigurationSet> captor = ArgumentCaptor.forClass(ConfigurationSet.class);
        verify(ses).createConfigurationSet(captor.capture(), eq(REGION));
        ConfigurationSet cs = captor.getValue();
        assertTrue(cs.getName().startsWith("my-stack-Res-"), cs.getName());
        assertTrue(cs.getName().length() <= 64);
        assertNull(cs.getSendingEnabled());
        assertNull(cs.getReputationMetricsEnabled());
        assertNull(cs.getSuppressionOptions());
    }

    @Test
    void configurationSetUpdateReconcilesInPlace() {
        ConfigurationSet existing = new ConfigurationSet("cs1");
        existing.setReputationMetricsEnabled(false);
        when(configSets.find("cs1", REGION)).thenReturn(Optional.of(existing));
        when(configSets.get("cs1", REGION)).thenReturn(existing);
        when(configSets.listTags("cs1", REGION)).thenReturn(List.of(new Tag("stale", "x"), new Tag("env", "a")));
        StackResource r = resource("AWS::SES::ConfigurationSet", "cs1");

        provisioner.provision(r, json("""
                {"Name": "cs1", "SendingOptions": {"SendingEnabled": false},
                 "Tags": [{"Key": "env", "Value": "b"}]}
                """), ctx("cs1"));

        verify(ses, never()).createConfigurationSet(any(), any());
        assertFalse(existing.getSendingEnabled());
        assertNull(existing.getReputationMetricsEnabled());
        verify(ses).setConfigurationSetTrackingOptions("cs1", null, REGION);
        verify(ses).setConfigurationSetDeliveryOptions("cs1", null, REGION);
        verify(configSets).save(existing, REGION);
        verify(configSets).untag("cs1", REGION, List.of("stale"));
        verify(configSets).tag("cs1", REGION, List.of(new Tag("env", "b")));
        assertEquals("cs1", r.getPhysicalId());
    }

    @Test
    void configurationSetRenameIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("AWS::SES::ConfigurationSet", "cs1"), json("{\"Name\": \"cs2\"}"), ctx("cs1")));
        assertTrue(e.getMessage().contains("Name"));
        verify(ses, never()).createConfigurationSet(any(), any());
    }

    @Test
    void configurationSetDeleteToleratesOnlyAnAlreadyGoneSet() {
        doThrow(new AwsException("ConfigurationSetDoesNotExist", "gone", 400))
                .when(ses).deleteConfigurationSet("gone", REGION);
        doThrow(new AwsException("BadRequestException", "tenant associations", 400))
                .when(ses).deleteConfigurationSet("held", REGION);

        assertDoesNotThrow(() -> provisioner.delete(resourceWithId("AWS::SES::ConfigurationSet", "gone"), REGION));
        assertThrows(AwsException.class,
                () -> provisioner.delete(resourceWithId("AWS::SES::ConfigurationSet", "held"), REGION));
    }

    // ──────────────────────── ConfigurationSetEventDestination ────────────────────────

    @Test
    void eventDestinationCreateMapsTheV1FieldNamesAndEventTypes() {
        StackResource r = resource("AWS::SES::ConfigurationSetEventDestination", null);

        provisioner.provision(r, json("""
                {"ConfigurationSetName": "cs1",
                 "EventDestination": {"Name": "to-sns", "Enabled": true,
                   "MatchingEventTypes": ["send", "renderingFailure", "DELIVERY_DELAY"],
                   "SnsDestination": {"TopicARN": "%s"}}}
                """.formatted(TOPIC)), ctx(null));

        ArgumentCaptor<EventDestination> captor = ArgumentCaptor.forClass(EventDestination.class);
        verify(configSets).createEventDestination(eq("cs1"), eq("to-sns"), captor.capture(), eq(ACCOUNT), eq(REGION));
        EventDestination dest = captor.getValue();
        assertTrue(dest.isEnabled());
        assertEquals(List.of("SEND", "RENDERING_FAILURE", "DELIVERY_DELAY"), dest.getMatchingEventTypes());
        assertEquals(TOPIC, dest.getSnsDestination().getTopicArn());
        assertEquals("to-sns", r.getPhysicalId());
        assertEquals("to-sns", r.getAttributes().get("Id"));
    }

    @Test
    void eventDestinationFirehoseAndCloudWatchFieldsAreMapped() {
        provisioner.provision(resource("AWS::SES::ConfigurationSetEventDestination", null), json("""
                {"ConfigurationSetName": "cs1",
                 "EventDestination": {"Name": "fh", "MatchingEventTypes": ["bounce"],
                   "KinesisFirehoseDestination": {"IAMRoleARN": "role", "DeliveryStreamARN": "stream"}}}
                """), ctx(null));
        provisioner.provision(resource("AWS::SES::ConfigurationSetEventDestination", null), json("""
                {"ConfigurationSetName": "cs1",
                 "EventDestination": {"Name": "cw", "MatchingEventTypes": ["open"],
                   "CloudWatchDestination": {"DimensionConfigurations": [{"DimensionName": "d",
                     "DimensionValueSource": "messageTag", "DefaultDimensionValue": "v"}]}}}
                """), ctx(null));

        ArgumentCaptor<EventDestination> fh = ArgumentCaptor.forClass(EventDestination.class);
        verify(configSets).createEventDestination(eq("cs1"), eq("fh"), fh.capture(), any(), any());
        assertEquals("role", fh.getValue().getKinesisFirehoseDestination().getIamRoleArn());
        assertEquals("stream", fh.getValue().getKinesisFirehoseDestination().getDeliveryStreamArn());
        assertFalse(fh.getValue().isEnabled());
        ArgumentCaptor<EventDestination> cw = ArgumentCaptor.forClass(EventDestination.class);
        verify(configSets).createEventDestination(eq("cs1"), eq("cw"), cw.capture(), any(), any());
        assertEquals("messageTag", cw.getValue().getCloudWatchDestination()
                .getDimensionConfigurations().get(0).getDimensionValueSource());
    }

    @Test
    void eventDestinationUpdateReplacesTheEntryInPlace() {
        ConfigurationSet cs = new ConfigurationSet("cs1");
        EventDestination stored = new EventDestination();
        stored.setName("to-sns");
        cs.getEventDestinations().add(stored);
        when(configSets.find("cs1", REGION)).thenReturn(Optional.of(cs));
        StackResource r = resource("AWS::SES::ConfigurationSetEventDestination", "to-sns");
        r.getAttributes().put("__FlociSesConfigurationSetName", "cs1");

        provisioner.provision(r, json("""
                {"ConfigurationSetName": "cs1",
                 "EventDestination": {"Name": "to-sns", "MatchingEventTypes": ["send"],
                   "SnsDestination": {"TopicARN": "%s"}}}
                """.formatted(TOPIC)), ctx("to-sns"));

        verify(configSets).updateEventDestination(eq("cs1"), eq("to-sns"), any(), eq(ACCOUNT), eq(REGION));
        verify(configSets, never()).createEventDestination(any(), any(), any(), any(), any());
    }

    @Test
    void eventDestinationDeleteUsesTheOwningSetAndToleratesAGoneSet() {
        StackResource r = resourceWithId("AWS::SES::ConfigurationSetEventDestination", "to-sns");
        r.getAttributes().put("__FlociSesConfigurationSetName", "cs1");
        doThrow(new AwsException("ConfigurationSetDoesNotExist", "gone", 400))
                .when(configSets).deleteEventDestination("cs1", "to-sns", REGION);

        assertDoesNotThrow(() -> provisioner.delete(r, REGION));
        verify(configSets).deleteEventDestination("cs1", "to-sns", REGION);
    }

    // ──────────────────────────── EmailIdentity ────────────────────────────

    @Test
    void domainIdentityExposesItsDkimRecordsAsAttributes() {
        Identity identity = new Identity("example.com", "Domain");
        identity.setDkimTokens(List.of("aaa", "bbb", "ccc"));
        when(identities.find("example.com", REGION)).thenReturn(Optional.of(identity));
        when(identities.effectiveDkimSource(identity, REGION)).thenReturn(identity);
        StackResource r = resource("AWS::SES::EmailIdentity", null);

        provisioner.provision(r, json("""
                {"EmailIdentity": "example.com",
                 "ConfigurationSetAttributes": {"ConfigurationSetName": "cs1"},
                 "DkimAttributes": {"SigningEnabled": false},
                 "FeedbackAttributes": {"EmailForwardingEnabled": false},
                 "MailFromAttributes": {"MailFromDomain": "mail.example.com", "BehaviorOnMxFailure": "REJECT_MESSAGE"},
                 "DkimSigningAttributes": {"NextSigningKeyLength": "RSA_2048_BIT"}}
                """), ctx(null));

        verify(ses).createEmailIdentity("example.com", "cs1", List.of(), REGION);
        verify(identities).setDkimAttributes("example.com", false, REGION);
        verify(identities).setFeedbackForwardingEnabled("example.com", false, REGION);
        verify(identities).setMailFromDomain("example.com", "mail.example.com", "RejectMessage", REGION);
        verify(identities).putDkimSigningAttributes("example.com", "AWS_SES", null, "RSA_2048_BIT", REGION);
        assertEquals("example.com", r.getPhysicalId());
        assertEquals("aaa._domainkey.example.com", r.getAttributes().get("DkimDNSTokenName1"));
        assertEquals("ccc._domainkey.example.com", r.getAttributes().get("DkimDNSTokenName3"));
        assertEquals("bbb.dkim.amazonses.com", r.getAttributes().get("DkimDNSTokenValue2"));
    }

    @Test
    void emailAddressIdentityCreateLeavesUnsetAttributesAlone() {
        Identity identity = new Identity("me@example.com", "EmailAddress");
        when(identities.find("me@example.com", REGION)).thenReturn(Optional.of(identity));
        when(identities.effectiveDkimSource(identity, REGION)).thenReturn(identity);
        StackResource r = resource("AWS::SES::EmailIdentity", null);

        provisioner.provision(r, json("{\"EmailIdentity\": \"me@example.com\"}"), ctx(null));

        verify(ses).createEmailIdentity("me@example.com", null, List.of(), REGION);
        verify(identities, never()).setDkimAttributes(anyString(), any(Boolean.class), anyString());
        verify(identities, never()).setMailFromDomain(any(), any(), any(), any());
        assertEquals("", r.getAttributes().get("DkimDNSTokenName1"));
    }

    @Test
    void identityUpdateClearsDroppedSettings() {
        Identity identity = new Identity("example.com", "Domain");
        when(identities.find("example.com", REGION)).thenReturn(Optional.of(identity));
        when(identities.effectiveDkimSource(identity, REGION)).thenReturn(identity);
        when(identities.listTags("example.com", REGION)).thenReturn(List.of(new Tag("old", "1")));

        provisioner.provision(resource("AWS::SES::EmailIdentity", "example.com"),
                json("{\"EmailIdentity\": \"example.com\"}"), ctx("example.com"));

        verify(ses, never()).createEmailIdentity(any(), any(), anyList(), any());
        verify(ses).setEmailIdentityConfigurationSet("example.com", null, REGION);
        verify(identities).setDkimAttributes("example.com", true, REGION);
        verify(identities).setFeedbackForwardingEnabled("example.com", true, REGION);
        verify(identities).setMailFromDomain("example.com", "", null, REGION);
        verify(identities).untag("example.com", REGION, List.of("old"));
    }

    @Test
    void identityRenameIsRefused() {
        assertThrows(AwsException.class, () -> provisioner.provision(resource("AWS::SES::EmailIdentity", "a.com"),
                json("{\"EmailIdentity\": \"b.com\"}"), ctx("a.com")));
    }

    // ──────────────────────────── DedicatedIpPool ────────────────────────────

    @Test
    void dedicatedIpPoolCreateAndScalingUpdate() {
        StackResource r = resource("AWS::SES::DedicatedIpPool", null);
        provisioner.provision(r, json("{\"PoolName\": \"pool1\", \"ScalingMode\": \"STANDARD\"}"), ctx(null));
        verify(dedicatedIps).createDedicatedIpPool("pool1", "STANDARD", List.of(), REGION);
        assertEquals("pool1", r.getPhysicalId());

        when(dedicatedIps.dedicatedIpPoolExists("pool1", REGION)).thenReturn(true);
        when(dedicatedIps.getDedicatedIpPool("pool1", REGION)).thenReturn(new DedicatedIpPool("pool1", "STANDARD"));
        when(dedicatedIps.listTags("pool1", REGION)).thenReturn(List.of());
        provisioner.provision(resource("AWS::SES::DedicatedIpPool", "pool1"),
                json("{\"PoolName\": \"pool1\", \"ScalingMode\": \"MANAGED\"}"), ctx("pool1"));
        verify(dedicatedIps).putDedicatedIpPoolScalingAttributes("pool1", "MANAGED", REGION);
    }

    @Test
    void unnamedPoolGetsALowercaseGeneratedName() {
        provisioner.provision(resource("AWS::SES::DedicatedIpPool", null), json("{}"), ctx(null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(dedicatedIps).createDedicatedIpPool(name.capture(), isNull(), anyList(), eq(REGION));
        assertEquals(name.getValue().toLowerCase(), name.getValue());
        assertTrue(name.getValue().startsWith("my-stack-res-"), name.getValue());
    }

    @Test
    void poolDeleteToleratesAGonePool() {
        doThrow(new AwsException("NotFoundException", "gone", 404))
                .when(dedicatedIps).deleteDedicatedIpPool("pool1", REGION);
        assertDoesNotThrow(() -> provisioner.delete(resourceWithId("AWS::SES::DedicatedIpPool", "pool1"), REGION));
    }

    private static StackResource resourceWithId(String type, String physicalId) {
        return resource(type, physicalId);
    }
}
