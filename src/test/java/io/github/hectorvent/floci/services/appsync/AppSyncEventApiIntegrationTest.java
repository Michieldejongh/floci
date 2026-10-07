package io.github.hectorvent.floci.services.appsync;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * The Event API control plane on {@code /v2/apis}, which API Gateway v2 also serves: the SigV4
 * signing service decides which of the two answers. Needs no GraphQL sidecar.
 */
@QuarkusTest
class AppSyncEventApiIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/appsync/aws4_request";
    private static final String APIGW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/apigateway/aws4_request";

    private static final String EVENT_CONFIG = """
            {"authProviders": [{"authType": "API_KEY"}],
             "connectionAuthModes": [{"authType": "API_KEY"}],
             "defaultPublishAuthModes": [{"authType": "API_KEY"}],
             "defaultSubscribeAuthModes": [{"authType": "API_KEY"}]}
            """;

    @Test
    void eventApiLifecycleWithChannelNamespaces() {
        String apiId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\": \"events-it\", \"ownerContact\": \"team\", \"eventConfig\": " + EVENT_CONFIG
                        + ", \"tags\": {\"env\": \"dev\"}}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(200)
                .body("api.name", equalTo("events-it"))
                .body("api.apiArn", startsWith("arn:aws:appsync:us-east-1:000000000000:apis/"))
                .body("api.dns.HTTP", notNullValue())
                .body("api.dns.REALTIME", notNullValue())
                .body("api.created", notNullValue())
                .body("api.xrayEnabled", equalTo(false))
                .body("api.tags.env", equalTo("dev"))
                .body("api.eventConfig.authProviders[0].authType", equalTo("API_KEY"))
                .extract().path("api.apiId");

        given().header("Authorization", AUTH)
            .when().get("/v2/apis/" + apiId)
            .then().statusCode(200)
                .body("api.apiId", equalTo(apiId))
                .body("api.dns.HTTP", equalTo("localhost:4566/v2/apis/" + apiId));

        given().header("Authorization", AUTH)
            .when().get("/v2/apis")
            .then().statusCode(200)
                .body("apis.apiId", hasItem(apiId));

        // Not a GraphQL API, and not an API Gateway v2 API either.
        given().header("Authorization", AUTH).when().get("/v1/apis/" + apiId).then().statusCode(404);
        given().header("Authorization", APIGW_AUTH)
            .when().get("/v2/apis")
            .then().statusCode(200)
                .body("items.apiId", not(hasItem(apiId)));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\": \"events-renamed\", \"eventConfig\": " + EVENT_CONFIG + "}")
            .when()
                .post("/v2/apis/" + apiId)
            .then()
                .statusCode(200)
                .body("api.name", equalTo("events-renamed"))
                .body("api.tags.env", equalTo("dev"));

        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"name": "chat",
                     "publishAuthModes": [{"authType": "API_KEY"}],
                     "handlerConfigs": {"onPublish": {"behavior": "DIRECT",
                       "integration": {"dataSourceName": "fn", "lambdaConfig": {"invokeType": "EVENT"}}}}}
                    """)
            .when()
                .post("/v2/apis/" + apiId + "/channelNamespaces")
            .then()
                .statusCode(200)
                .body("channelNamespace.channelNamespaceArn",
                        equalTo("arn:aws:appsync:us-east-1:000000000000:apis/" + apiId + "/channelNamespaces/chat"))
                .body("channelNamespace.publishAuthModes[0].authType", equalTo("API_KEY"))
                .body("channelNamespace.handlerConfigs.onPublish.integration.lambdaConfig.invokeType",
                        equalTo("EVENT"));

        given().header("Authorization", AUTH).when().delete("/v2/apis/" + apiId).then().statusCode(204);
        given().header("Authorization", AUTH).when().get("/v2/apis/" + apiId).then().statusCode(404)
                .body("message", notNullValue());
        given().header("Authorization", AUTH)
            .when().get("/v2/apis/" + apiId + "/channelNamespaces")
            .then().statusCode(200)
                .body("channelNamespaces.size()", equalTo(0));
    }

    @Test
    void anEventApiTakesApiKeysAndDataSources() {
        String apiId = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\": \"events-keys-it\", \"eventConfig\": " + EVENT_CONFIG + "}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(200)
                .extract().path("api.apiId");

        given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"description\": \"bench\"}")
            .when().post("/v1/apis/" + apiId + "/apikeys")
            .then().statusCode(200)
                .body("apiKey.id", notNullValue());

        given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"name\": \"none_ds\", \"type\": \"NONE\"}")
            .when().post("/v1/apis/" + apiId + "/datasources")
            .then().statusCode(200)
                .body("dataSource.name", equalTo("none_ds"));

        given().header("Authorization", AUTH).when().delete("/v2/apis/" + apiId).then().statusCode(204);

        given().header("Authorization", AUTH).contentType("application/json")
                .body("{\"description\": \"gone\"}")
            .when().post("/v1/apis/" + apiId + "/apikeys")
            .then().statusCode(404);
    }

    @Test
    void channelNamespaceOnAnUnknownApiIsNotFound() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\": \"chat\"}")
            .when()
                .post("/v2/apis/nosuchapi0000000000000000000/channelNamespaces")
            .then()
                .statusCode(404);
    }

    @Test
    void createApiValidatesTheEventConfig() {
        given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"name\": \"bad\", \"eventConfig\": {\"authProviders\": [{\"authType\": \"API_KEY\"}]}}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(400);
    }

    @Test
    void apiGatewayV2StillOwnsUnsignedAndApiGatewaySignedRequests() {
        given()
                .header("Authorization", APIGW_AUTH)
                .contentType("application/json")
                .body("{\"name\": \"http-api\", \"protocolType\": \"HTTP\"}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(201)
                .body("protocolType", equalTo("HTTP"));

        given()
                .contentType("application/json")
                .body("{\"name\": \"unsigned-http-api\", \"protocolType\": \"HTTP\"}")
            .when()
                .post("/v2/apis")
            .then()
                .statusCode(201)
                .body("protocolType", equalTo("HTTP"));
    }
}
