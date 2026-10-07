package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventConfig {
    private List<AuthProvider> authProviders;
    private List<AuthMode> connectionAuthModes;
    private List<AuthMode> defaultPublishAuthModes;
    private List<AuthMode> defaultSubscribeAuthModes;
    private EventLogConfig logConfig;

    public List<AuthProvider> getAuthProviders() { return authProviders; }
    public void setAuthProviders(List<AuthProvider> authProviders) { this.authProviders = authProviders; }

    public List<AuthMode> getConnectionAuthModes() { return connectionAuthModes; }
    public void setConnectionAuthModes(List<AuthMode> connectionAuthModes) { this.connectionAuthModes = connectionAuthModes; }

    public List<AuthMode> getDefaultPublishAuthModes() { return defaultPublishAuthModes; }
    public void setDefaultPublishAuthModes(List<AuthMode> defaultPublishAuthModes) { this.defaultPublishAuthModes = defaultPublishAuthModes; }

    public List<AuthMode> getDefaultSubscribeAuthModes() { return defaultSubscribeAuthModes; }
    public void setDefaultSubscribeAuthModes(List<AuthMode> defaultSubscribeAuthModes) { this.defaultSubscribeAuthModes = defaultSubscribeAuthModes; }

    public EventLogConfig getLogConfig() { return logConfig; }
    public void setLogConfig(EventLogConfig logConfig) { this.logConfig = logConfig; }
}
