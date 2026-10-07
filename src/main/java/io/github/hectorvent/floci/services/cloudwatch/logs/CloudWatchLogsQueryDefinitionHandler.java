package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsQueryDefinitionService.DescribeResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryDefinition;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryParameter;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire shapes of the saved query definition operations, reached through
 * {@link CloudWatchLogsHandler}.
 */
class CloudWatchLogsQueryDefinitionHandler {

    private final CloudWatchLogsQueryDefinitionService queryDefinitions;
    private final ObjectMapper objectMapper;

    CloudWatchLogsQueryDefinitionHandler(CloudWatchLogsQueryDefinitionService queryDefinitions,
                                         ObjectMapper objectMapper) {
        this.queryDefinitions = queryDefinitions;
        this.objectMapper = objectMapper;
    }

    Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutQueryDefinition" -> putQueryDefinition(request, region);
            case "DescribeQueryDefinitions" -> describeQueryDefinitions(request, region);
            case "DeleteQueryDefinition" -> deleteQueryDefinition(request, region);
            default -> throw new IllegalStateException("not a query definition action: " + action);
        };
    }

    private Response putQueryDefinition(JsonNode request, String region) {
        String id = queryDefinitions.putQueryDefinition(
                text(request, "queryDefinitionId"),
                text(request, "name"),
                text(request, "queryString"),
                strings(request, "logGroupNames"),
                parameters(request),
                text(request, "queryLanguage"),
                text(request, "clientToken"),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("queryDefinitionId", id);
        return Response.ok(response).build();
    }

    private Response describeQueryDefinitions(JsonNode request, String region) {
        Integer maxResults = null;
        JsonNode max = request.get("maxResults");
        if (max != null && !max.isNull()) {
            if (!max.canConvertToInt()) {
                throw invalid("maxResults must be an integer.");
            }
            maxResults = max.intValue();
        }
        DescribeResult result = queryDefinitions.describeQueryDefinitions(
                text(request, "queryDefinitionNamePrefix"),
                text(request, "queryLanguage"),
                maxResults,
                text(request, "nextToken"),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("queryDefinitions");
        for (QueryDefinition definition : result.queryDefinitions()) {
            items.add(toNode(definition));
        }
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response deleteQueryDefinition(JsonNode request, String region) {
        queryDefinitions.deleteQueryDefinition(text(request, "queryDefinitionId"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", true);
        return Response.ok(response).build();
    }

    private ObjectNode toNode(QueryDefinition definition) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("queryDefinitionId", definition.getQueryDefinitionId());
        node.put("name", definition.getName());
        node.put("queryString", definition.getQueryString());
        node.put("lastModified", definition.getLastModified());
        if (!definition.getLogGroupNames().isEmpty()) {
            ArrayNode groups = node.putArray("logGroupNames");
            definition.getLogGroupNames().forEach(groups::add);
        }
        if (!definition.getParameters().isEmpty()) {
            ArrayNode params = node.putArray("parameters");
            for (QueryParameter parameter : definition.getParameters()) {
                ObjectNode param = params.addObject();
                param.put("name", parameter.getName());
                if (parameter.getDefaultValue() != null) {
                    param.put("defaultValue", parameter.getDefaultValue());
                }
                if (parameter.getDescription() != null) {
                    param.put("description", parameter.getDescription());
                }
            }
        }
        node.put("queryLanguage", definition.getQueryLanguage());
        return node;
    }

    private static List<QueryParameter> parameters(JsonNode request) {
        JsonNode node = request.get("parameters");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw invalid("parameters must be a list.");
        }
        List<QueryParameter> parameters = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw invalid("parameters must contain objects.");
            }
            parameters.add(new QueryParameter(text(item, "name"), text(item, "defaultValue"),
                    text(item, "description")));
        }
        return parameters;
    }

    private static List<String> strings(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw invalid(member + " must be a list of strings.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw invalid(member + " must be a list of strings.");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(member + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
