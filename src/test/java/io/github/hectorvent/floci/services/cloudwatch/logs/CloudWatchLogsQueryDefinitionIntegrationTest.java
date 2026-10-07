package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CloudWatchLogsQueryDefinitionIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT_A =
            "AWS4-HMAC-SHA256 Credential=000000000031/20261007/us-east-1/logs/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String ACCOUNT_B =
            "AWS4-HMAC-SHA256 Credential=000000000032/20261007/us-east-1/logs/aws4_request, SignedHeaders=host, Signature=abc";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void queryDefinitionLifecycle() {
        String id = call(ACCOUNT_A, "PutQueryDefinition",
                "{\"name\":\"qd-lifecycle/errors\",\"queryString\":\"fields @message | filter @message like /ERROR/\","
                        + "\"logGroupNames\":[\"/app/one\",\"/app/two\"]}")
                .then().statusCode(200)
                .body("queryDefinitionId", notNullValue())
                .extract().path("queryDefinitionId");

        call(ACCOUNT_A, "DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"qd-lifecycle/\"}")
                .then().statusCode(200)
                .body("queryDefinitions", hasSize(1))
                .body("queryDefinitions[0].queryDefinitionId", equalTo(id))
                .body("queryDefinitions[0].name", equalTo("qd-lifecycle/errors"))
                .body("queryDefinitions[0].logGroupNames", contains("/app/one", "/app/two"))
                .body("queryDefinitions[0].queryLanguage", equalTo("CWLI"))
                .body("queryDefinitions[0].lastModified", notNullValue());

        call(ACCOUNT_A, "PutQueryDefinition",
                "{\"queryDefinitionId\":\"" + id + "\",\"name\":\"qd-lifecycle/errors\",\"queryString\":\"fields @message\"}")
                .then().statusCode(200).body("queryDefinitionId", equalTo(id));

        call(ACCOUNT_A, "DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"qd-lifecycle/\"}")
                .then().statusCode(200)
                .body("queryDefinitions[0].queryString", equalTo("fields @message"))
                .body("queryDefinitions[0].logGroupNames", nullValue());

        call(ACCOUNT_A, "DeleteQueryDefinition", "{\"queryDefinitionId\":\"" + id + "\"}")
                .then().statusCode(200).body("success", equalTo(true));

        call(ACCOUNT_A, "DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"qd-lifecycle/\"}")
                .then().statusCode(200).body("queryDefinitions", hasSize(0));
    }

    @Test
    void unknownIdIsResourceNotFound() {
        call(ACCOUNT_A, "DeleteQueryDefinition", "{\"queryDefinitionId\":\"does-not-exist\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
        call(ACCOUNT_A, "PutQueryDefinition",
                "{\"queryDefinitionId\":\"does-not-exist\",\"name\":\"n\",\"queryString\":\"fields @message\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void missingNameIsInvalidParameter() {
        call(ACCOUNT_A, "PutQueryDefinition", "{\"queryString\":\"fields @message\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void describePaginates() {
        for (String suffix : new String[] {"a", "b", "c"}) {
            call(ACCOUNT_A, "PutQueryDefinition",
                    "{\"name\":\"qd-page/" + suffix + "\",\"queryString\":\"fields @message\"}")
                    .then().statusCode(200);
        }
        String token = call(ACCOUNT_A, "DescribeQueryDefinitions",
                "{\"queryDefinitionNamePrefix\":\"qd-page/\",\"maxResults\":2}")
                .then().statusCode(200)
                .body("queryDefinitions.name", contains("qd-page/a", "qd-page/b"))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");

        call(ACCOUNT_A, "DescribeQueryDefinitions",
                "{\"queryDefinitionNamePrefix\":\"qd-page/\",\"maxResults\":2,\"nextToken\":\"" + token + "\"}")
                .then().statusCode(200)
                .body("queryDefinitions.name", contains("qd-page/c"))
                .body("nextToken", nullValue());
    }

    @Test
    void definitionsAreIsolatedPerAccount() {
        call(ACCOUNT_A, "PutQueryDefinition", "{\"name\":\"qd-isolated\",\"queryString\":\"fields @message\"}")
                .then().statusCode(200);

        call(ACCOUNT_B, "DescribeQueryDefinitions", "{\"queryDefinitionNamePrefix\":\"qd-isolated\"}")
                .then().statusCode(200).body("queryDefinitions", hasSize(0));
    }

    private static Response call(String auth, String action, String body) {
        return given().contentType(CONTENT_TYPE).header("Authorization", auth)
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(body).post("/");
    }
}
