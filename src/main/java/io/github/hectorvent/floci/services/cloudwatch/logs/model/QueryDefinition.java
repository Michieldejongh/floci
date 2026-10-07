package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryDefinition {

    private String queryDefinitionId;
    private String name;
    private String queryString;
    private List<String> logGroupNames = new ArrayList<>();
    private List<QueryParameter> parameters = new ArrayList<>();
    private String queryLanguage;
    private long lastModified;

    public QueryDefinition() {}

    public String getQueryDefinitionId() { return queryDefinitionId; }
    public void setQueryDefinitionId(String queryDefinitionId) { this.queryDefinitionId = queryDefinitionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getQueryString() { return queryString; }
    public void setQueryString(String queryString) { this.queryString = queryString; }

    public List<String> getLogGroupNames() { return logGroupNames; }
    public void setLogGroupNames(List<String> logGroupNames) {
        this.logGroupNames = logGroupNames == null ? new ArrayList<>() : logGroupNames;
    }

    public List<QueryParameter> getParameters() { return parameters; }
    public void setParameters(List<QueryParameter> parameters) {
        this.parameters = parameters == null ? new ArrayList<>() : parameters;
    }

    public String getQueryLanguage() { return queryLanguage; }
    public void setQueryLanguage(String queryLanguage) { this.queryLanguage = queryLanguage; }

    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
}
