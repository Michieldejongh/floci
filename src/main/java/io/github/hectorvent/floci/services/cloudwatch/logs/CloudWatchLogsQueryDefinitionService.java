package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryDefinition;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryParameter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Saved Logs Insights query definitions: PutQueryDefinition, DescribeQueryDefinitions and
 * DeleteQueryDefinition. Definitions are keyed by region and id; the account-aware store adds
 * the calling account.
 */
@ApplicationScoped
public class CloudWatchLogsQueryDefinitionService {

    static final int DEFAULT_MAX_RESULTS = 1000;
    private static final Set<String> QUERY_LANGUAGES = Set.of("CWLI", "SQL", "PPL");
    private static final String DEFAULT_QUERY_LANGUAGE = "CWLI";
    private static final Pattern LOG_GROUP_NAME = Pattern.compile("[.\\-_/#A-Za-z0-9]+");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("\\S{36,128}");
    private static final int MAX_PARAMETERS = 20;
    private static final char TOKEN_SEPARATOR = '\u0000';
    private static final Comparator<QueryDefinition> ORDER =
            Comparator.comparing(QueryDefinition::getName).thenComparing(QueryDefinition::getQueryDefinitionId);

    private final StorageBackend<String, QueryDefinition> definitions;
    private final LongSupplier clock;

    @Inject
    public CloudWatchLogsQueryDefinitionService(StorageFactory storageFactory) {
        this(storageFactory.create("cloudwatchlogs", "cwlogs-query-definitions.json",
                new TypeReference<Map<String, QueryDefinition>>() {}), System::currentTimeMillis);
    }

    CloudWatchLogsQueryDefinitionService(StorageBackend<String, QueryDefinition> definitions, LongSupplier clock) {
        this.definitions = definitions;
        this.clock = clock;
    }

    public record DescribeResult(List<QueryDefinition> queryDefinitions, String nextToken) {}

    public synchronized String putQueryDefinition(String queryDefinitionId, String name, String queryString,
                                                  List<String> logGroupNames, List<QueryParameter> parameters,
                                                  String queryLanguage, String clientToken, String region) {
        requireLength(name, "name", 1, 255);
        requireLength(queryString, "queryString", 1, 10_000);
        if (queryDefinitionId != null) {
            requireLength(queryDefinitionId, "queryDefinitionId", 1, 256);
        }
        if (clientToken != null && !CLIENT_TOKEN.matcher(clientToken).matches()) {
            throw invalid("clientToken must be 36 to 128 non-whitespace characters.");
        }
        validateQueryLanguage(queryLanguage);
        List<String> groups = logGroupNames == null ? List.of() : logGroupNames;
        for (String group : groups) {
            if (group == null || group.isEmpty() || group.length() > 512
                    || !LOG_GROUP_NAME.matcher(group).matches()) {
                throw invalid("logGroupNames contains an invalid log group name.");
            }
        }
        List<QueryParameter> params = parameters == null ? List.of() : parameters;
        if (params.size() > MAX_PARAMETERS) {
            throw invalid("parameters must contain at most " + MAX_PARAMETERS + " items.");
        }
        for (QueryParameter parameter : params) {
            if (parameter.getName() == null || parameter.getName().isEmpty()) {
                throw invalid("Each query parameter requires a name.");
            }
        }

        String id = queryDefinitionId;
        if (id == null) {
            id = UUID.randomUUID().toString();
        } else if (definitions.get(key(region, id)).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The specified query definition does not exist.", 400);
        }

        QueryDefinition definition = new QueryDefinition();
        definition.setQueryDefinitionId(id);
        definition.setName(name);
        definition.setQueryString(queryString);
        definition.setLogGroupNames(new ArrayList<>(groups));
        definition.setParameters(new ArrayList<>(params));
        definition.setQueryLanguage(queryLanguage == null ? DEFAULT_QUERY_LANGUAGE : queryLanguage);
        definition.setLastModified(clock.getAsLong());
        definitions.put(key(region, id), definition);
        return id;
    }

    public DescribeResult describeQueryDefinitions(String namePrefix, String queryLanguage, Integer maxResults,
                                                   String nextToken, String region) {
        if (namePrefix != null) {
            requireLength(namePrefix, "queryDefinitionNamePrefix", 1, 255);
        }
        validateQueryLanguage(queryLanguage);
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        if (limit < 1 || limit > DEFAULT_MAX_RESULTS) {
            throw invalid("maxResults must be between 1 and " + DEFAULT_MAX_RESULTS + ".");
        }
        QueryDefinition after = nextToken == null ? null : decodeToken(nextToken);

        List<QueryDefinition> matching = definitions.scan(k -> k.startsWith(region + "::")).stream()
                .filter(d -> namePrefix == null || d.getName().startsWith(namePrefix))
                .filter(d -> queryLanguage == null || queryLanguage.equals(d.getQueryLanguage()))
                .filter(d -> after == null || ORDER.compare(d, after) > 0)
                .sorted(ORDER)
                .toList();
        if (matching.size() <= limit) {
            return new DescribeResult(matching, null);
        }
        List<QueryDefinition> page = matching.subList(0, limit);
        return new DescribeResult(page, encodeToken(page.get(limit - 1)));
    }

    public synchronized void deleteQueryDefinition(String queryDefinitionId, String region) {
        requireLength(queryDefinitionId, "queryDefinitionId", 1, 256);
        String key = key(region, queryDefinitionId);
        if (definitions.get(key).isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "The specified query definition does not exist.", 400);
        }
        definitions.delete(key);
    }

    private static String encodeToken(QueryDefinition last) {
        String cursor = last.getName() + TOKEN_SEPARATOR + last.getQueryDefinitionId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    private static QueryDefinition decodeToken(String token) {
        String cursor;
        try {
            cursor = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid("The specified nextToken is invalid.");
        }
        int separator = cursor.lastIndexOf(TOKEN_SEPARATOR);
        if (separator < 0) {
            throw invalid("The specified nextToken is invalid.");
        }
        QueryDefinition after = new QueryDefinition();
        after.setName(cursor.substring(0, separator));
        after.setQueryDefinitionId(cursor.substring(separator + 1));
        return after;
    }

    private static void validateQueryLanguage(String queryLanguage) {
        if (queryLanguage != null && !QUERY_LANGUAGES.contains(queryLanguage)) {
            throw invalid("queryLanguage must be one of CWLI, SQL, PPL.");
        }
    }

    private static void requireLength(String value, String member, int min, int max) {
        if (value == null || value.length() < min || value.length() > max) {
            throw invalid(member + " must be between " + min + " and " + max + " characters.");
        }
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
