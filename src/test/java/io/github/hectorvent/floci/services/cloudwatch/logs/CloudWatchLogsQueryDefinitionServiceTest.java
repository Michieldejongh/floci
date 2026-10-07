package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsQueryDefinitionService.DescribeResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryDefinition;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.QueryParameter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchLogsQueryDefinitionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String QUERY = "fields @timestamp, @message | limit 20";

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final CloudWatchLogsQueryDefinitionService service =
            new CloudWatchLogsQueryDefinitionService(new InMemoryStorage<>(), now::get);

    @Test
    void putCreatesDefinitionWithDefaults() {
        String id = service.putQueryDefinition(null, "errors", QUERY, List.of("/app/one"), null, null, null, REGION);

        QueryDefinition definition = only(service.describeQueryDefinitions(null, null, null, null, REGION));
        assertEquals(id, definition.getQueryDefinitionId());
        assertEquals("errors", definition.getName());
        assertEquals(QUERY, definition.getQueryString());
        assertEquals(List.of("/app/one"), definition.getLogGroupNames());
        assertEquals("CWLI", definition.getQueryLanguage());
        assertEquals(1_700_000_000_000L, definition.getLastModified());
    }

    @Test
    void putWithExistingIdReplacesEveryField() {
        String id = service.putQueryDefinition(null, "errors", QUERY, List.of("/app/one"),
                List.of(new QueryParameter("level", "ERROR", null)), null, null, REGION);
        now.addAndGet(5_000);

        String updated = service.putQueryDefinition(id, "renamed", "fields @message", null, null, "PPL", null, REGION);

        assertEquals(id, updated);
        QueryDefinition definition = only(service.describeQueryDefinitions(null, null, null, null, REGION));
        assertEquals("renamed", definition.getName());
        assertEquals("fields @message", definition.getQueryString());
        assertTrue(definition.getLogGroupNames().isEmpty());
        assertTrue(definition.getParameters().isEmpty());
        assertEquals("PPL", definition.getQueryLanguage());
        assertEquals(1_700_000_005_000L, definition.getLastModified());
    }

    @Test
    void putWithUnknownIdIsResourceNotFound() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putQueryDefinition("missing", "n", QUERY, null, null, null, null, REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void putValidatesRequiredMembersAndEnums() {
        assertInvalid(() -> service.putQueryDefinition(null, null, QUERY, null, null, null, null, REGION));
        assertInvalid(() -> service.putQueryDefinition(null, "n", "", null, null, null, null, REGION));
        assertInvalid(() -> service.putQueryDefinition(null, "x".repeat(256), QUERY, null, null, null, null, REGION));
        assertInvalid(() -> service.putQueryDefinition(null, "n", QUERY, null, null, "KQL", null, REGION));
        assertInvalid(() -> service.putQueryDefinition(null, "n", QUERY, List.of("bad name!"), null, null, null,
                REGION));
        assertInvalid(() -> service.putQueryDefinition(null, "n", QUERY, null, null, null, "short", REGION));
    }

    @Test
    void describeFiltersByPrefixAndLanguage() {
        service.putQueryDefinition(null, "lambda-errors", QUERY, null, null, null, null, REGION);
        service.putQueryDefinition(null, "lambda-sql", "SELECT 1", null, null, "SQL", null, REGION);
        service.putQueryDefinition(null, "vpc-flows", QUERY, null, null, null, null, REGION);

        assertEquals(List.of("lambda-errors", "lambda-sql"),
                names(service.describeQueryDefinitions("lambda", null, null, null, REGION)));
        assertEquals(List.of("lambda-sql"),
                names(service.describeQueryDefinitions(null, "SQL", null, null, REGION)));
    }

    @Test
    void describePaginatesWithNextToken() {
        for (String name : List.of("c", "a", "e", "b", "d")) {
            service.putQueryDefinition(null, name, QUERY, null, null, null, null, REGION);
        }

        DescribeResult first = service.describeQueryDefinitions(null, null, 2, null, REGION);
        DescribeResult second = service.describeQueryDefinitions(null, null, 2, first.nextToken(), REGION);
        DescribeResult third = service.describeQueryDefinitions(null, null, 2, second.nextToken(), REGION);

        assertEquals(List.of("a", "b"), names(first));
        assertEquals(List.of("c", "d"), names(second));
        assertEquals(List.of("e"), names(third));
        assertNull(third.nextToken());
    }

    @Test
    void describeRejectsOutOfRangeMaxResultsAndBadToken() {
        assertInvalid(() -> service.describeQueryDefinitions(null, null, 0, null, REGION));
        assertInvalid(() -> service.describeQueryDefinitions(null, null, 1001, null, REGION));
        assertInvalid(() -> service.describeQueryDefinitions(null, null, null, "!!not-base64!!", REGION));
    }

    @Test
    void definitionsAreIsolatedPerRegion() {
        String id = service.putQueryDefinition(null, "errors", QUERY, null, null, null, null, REGION);

        assertTrue(service.describeQueryDefinitions(null, null, null, null, "eu-west-1").queryDefinitions().isEmpty());
        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteQueryDefinition(id, "eu-west-1"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    @Test
    void deleteRemovesDefinitionAndRejectsUnknownId() {
        String id = service.putQueryDefinition(null, "errors", QUERY, null, null, null, null, REGION);

        service.deleteQueryDefinition(id, REGION);

        assertTrue(service.describeQueryDefinitions(null, null, null, null, REGION).queryDefinitions().isEmpty());
        AwsException error = assertThrows(AwsException.class, () -> service.deleteQueryDefinition(id, REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
    }

    private static QueryDefinition only(DescribeResult result) {
        assertEquals(1, result.queryDefinitions().size());
        return result.queryDefinitions().get(0);
    }

    private static List<String> names(DescribeResult result) {
        return result.queryDefinitions().stream().map(QueryDefinition::getName).toList();
    }

    private static void assertInvalid(Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals("InvalidParameterException", error.getErrorCode());
    }
}
