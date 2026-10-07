package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AppConfigCfnProvisioner in isolation, against a mocked AppConfigService. */
class AppConfigCfnProvisionerTest {

    private final AppConfigService service = mock(AppConfigService.class);
    private final AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Application app(String id, String name) {
        Application a = new Application();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private static Environment env(String id, String appId) {
        Environment e = new Environment();
        e.setId(id);
        e.setApplicationId(appId);
        return e;
    }

    private static ConfigurationProfile profile(String id, String appId, String locationUri, String type) {
        ConfigurationProfile p = new ConfigurationProfile();
        p.setId(id);
        p.setApplicationId(appId);
        p.setLocationUri(locationUri);
        p.setType(type);
        return p;
    }

    private static AwsException notFound() {
        return new AwsException("ResourceNotFoundException", "not found", 404);
    }

    @Test
    void createsApplicationWithRefAndApplicationIdAndTags() {
        when(service.createApplication(any())).thenReturn(app("app1234", "my-app"));
        when(service.getApplicationTags("app1234")).thenReturn(Map.of());
        ObjectNode props = mapper.createObjectNode().put("Name", "my-app").put("Description", "d");
        props.putArray("Tags").addObject().put("Key", "team").put("Value", "core");
        StackResource r = resource("AWS::AppConfig::Application", "App");

        provisioner.provision(r, props, ctx(null));

        assertEquals("app1234", r.getPhysicalId());
        assertEquals("app1234", r.getAttributes().get("ApplicationId"));
        verify(service).createApplication(argThat(req -> "my-app".equals(req.get("Name")) && "d".equals(req.get("Description"))));
        verify(service).tagApplication("app1234", Map.of("team", "core"));
    }

    @Test
    void applicationWithoutNameFailsBeforeAnyCall() {
        StackResource r = resource("AWS::AppConfig::Application", "App");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx(null)));

        assertEquals("Model validation failed (#: required key [Name] not found)", e.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void updatesApplicationInPlaceClearingDroppedDescriptionAndTags() {
        when(service.getApplication("app1234")).thenReturn(app("app1234", "old"));
        when(service.updateApplication(eq("app1234"), any())).thenReturn(app("app1234", "new"));
        when(service.getApplicationTags("app1234")).thenReturn(Map.of("team", "core"));
        StackResource r = resource("AWS::AppConfig::Application", "App");

        provisioner.provision(r, mapper.createObjectNode().put("Name", "new"), ctx("app1234"));

        assertEquals("app1234", r.getPhysicalId());
        verify(service, never()).createApplication(any());
        verify(service).updateApplication(eq("app1234"),
                argThat(req -> "new".equals(req.get("Name")) && req.containsKey("Description") && req.get("Description") == null));
        verify(service).untagApplication("app1234", List.of("team"));
    }

    @Test
    void createsEnvironmentUnderTheApplicationNamedByApplicationId() {
        when(service.resolveApplication("my-app")).thenReturn(app("app1234", "my-app"));
        when(service.createEnvironment(eq("app1234"), any())).thenReturn(env("env5678", "app1234"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationId", "my-app").put("Name", "prod");
        props.putArray("Monitors").addObject().put("AlarmArn", "arn:aws:cloudwatch:us-east-1:000000000000:alarm:a");
        StackResource r = resource("AWS::AppConfig::Environment", "Env");

        provisioner.provision(r, props, ctx(null));

        assertEquals("env5678", r.getPhysicalId());
        assertEquals("env5678", r.getAttributes().get("EnvironmentId"));
        assertEquals("app1234", r.getAttributes().get("ApplicationId"));
    }

    @Test
    void environmentUpdatesInPlaceUnderTheSameApplication() {
        when(service.resolveApplication("app1234")).thenReturn(app("app1234", "my-app"));
        when(service.getEnvironment("app1234", "env5678")).thenReturn(env("env5678", "app1234"));
        when(service.updateEnvironment(eq("app1234"), eq("env5678"), any())).thenReturn(env("env5678", "app1234"));
        StackResource r = resource("AWS::AppConfig::Environment", "Env");

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationId", "app1234").put("Name", "prod2"),
                ctx("env5678"));

        assertEquals("env5678", r.getPhysicalId());
        verify(service, never()).createEnvironment(anyString(), any());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void environmentMovedToAnotherApplicationIsReplaced() {
        when(service.resolveApplication("app9999")).thenReturn(app("app9999", "other"));
        when(service.getEnvironment("app9999", "env5678")).thenThrow(notFound());
        when(service.createEnvironment(eq("app9999"), any())).thenReturn(env("envnew1", "app9999"));
        StackResource r = resource("AWS::AppConfig::Environment", "Env");
        r.setPhysicalId("env5678");
        r.getAttributes().put("EnvironmentId", "env5678");
        r.getAttributes().put("ApplicationId", "app1234");

        provisioner.provision(r, mapper.createObjectNode().put("ApplicationId", "app9999").put("Name", "prod"),
                ctx("env5678"));

        assertEquals("envnew1", r.getPhysicalId());
        assertEquals("app9999", r.getAttributes().get("ApplicationId"));
        assertEquals("env5678", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void createsHostedFreeformProfileByDefault() {
        when(service.resolveApplication("app1234")).thenReturn(app("app1234", "my-app"));
        when(service.createConfigurationProfile(eq("app1234"), any()))
                .thenReturn(profile("prof111", "app1234", "hosted", "AWS.Freeform"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationId", "app1234").put("Name", "cfg")
                .put("LocationUri", "hosted").put("KmsKeyIdentifier", "1234abcd-12ab-34cd-56ef-1234567890ab");
        StackResource r = resource("AWS::AppConfig::ConfigurationProfile", "Profile");

        provisioner.provision(r, props, ctx(null));

        assertEquals("prof111", r.getPhysicalId());
        assertEquals("prof111", r.getAttributes().get("ConfigurationProfileId"));
        assertEquals("app1234", r.getAttributes().get("ApplicationId"));
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab",
                r.getAttributes().get("KmsKeyArn"));
        verify(service).createConfigurationProfile(eq("app1234"), argThat(req ->
                "hosted".equals(req.get("LocationUri")) && "AWS.Freeform".equals(req.get("Type"))
                        && "cfg".equals(req.get("Name"))));
    }

    @Test
    void profileWithoutLocationUriFails() {
        when(service.resolveApplication("app1234")).thenReturn(app("app1234", "my-app"));
        StackResource r = resource("AWS::AppConfig::ConfigurationProfile", "Profile");

        assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("ApplicationId", "app1234").put("Name", "cfg"), ctx(null)));
        verify(service, never()).createConfigurationProfile(anyString(), any());
    }

    @Test
    void profileUpdatesInPlaceAndIsReplacedOnTypeChange() {
        when(service.resolveApplication("app1234")).thenReturn(app("app1234", "my-app"));
        when(service.getConfigurationProfile("app1234", "prof111"))
                .thenReturn(profile("prof111", "app1234", "hosted", "AWS.Freeform"));
        when(service.updateConfigurationProfile(eq("app1234"), eq("prof111"), any()))
                .thenReturn(profile("prof111", "app1234", "hosted", "AWS.Freeform"));
        when(service.createConfigurationProfile(eq("app1234"), any()))
                .thenReturn(profile("prof222", "app1234", "hosted", "AWS.AppConfig.FeatureFlags"));
        ObjectNode props = mapper.createObjectNode().put("ApplicationId", "app1234").put("Name", "renamed")
                .put("LocationUri", "hosted");

        StackResource inPlace = resource("AWS::AppConfig::ConfigurationProfile", "Profile");
        provisioner.provision(inPlace, props, ctx("prof111"));
        assertEquals("prof111", inPlace.getPhysicalId());
        verify(service, never()).createConfigurationProfile(anyString(), any());

        StackResource replaced = resource("AWS::AppConfig::ConfigurationProfile", "Profile");
        provisioner.provision(replaced, props.deepCopy().put("Type", "AWS.AppConfig.FeatureFlags"), ctx("prof111"));
        assertEquals("prof222", replaced.getPhysicalId());
        assertEquals("prof111", provisioner.updateCleanupPhysicalId(replaced));
    }

    @Test
    void deletesEnvironmentAndProfileUnderTheirOwningApplication() {
        when(service.listApplications()).thenReturn(List.of(app("appaaaa", "a"), app("app1234", "b")));
        when(service.listEnvironments("appaaaa")).thenReturn(List.of());
        when(service.listEnvironments("app1234")).thenReturn(List.of(env("env5678", "app1234")));
        when(service.listConfigurationProfiles("appaaaa")).thenReturn(List.of());
        when(service.listConfigurationProfiles("app1234"))
                .thenReturn(List.of(profile("prof111", "app1234", "hosted", "AWS.Freeform")));

        provisioner.delete("AWS::AppConfig::Environment", "env5678", "us-east-1");
        provisioner.delete("AWS::AppConfig::ConfigurationProfile", "prof111", "us-east-1");
        provisioner.delete("AWS::AppConfig::Application", "app1234", "us-east-1");

        verify(service).deleteEnvironment("app1234", "env5678");
        verify(service).deleteConfigurationProfile("app1234", "prof111");
        verify(service).deleteApplication("app1234");
    }

    @Test
    void deletingAnEnvironmentThatIsAlreadyGoneIsTolerated() {
        when(service.listApplications()).thenReturn(List.of(app("app1234", "b")));
        when(service.listEnvironments("app1234")).thenReturn(List.of());

        provisioner.delete("AWS::AppConfig::Environment", "env5678", "us-east-1");

        verify(service, never()).deleteEnvironment(anyString(), anyString());
    }
}
