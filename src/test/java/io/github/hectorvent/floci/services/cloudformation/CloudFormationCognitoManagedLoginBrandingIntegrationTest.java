package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.parsing.Parser;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::Cognito::ManagedLoginBranding} through CloudFormation and Cloud Control: the style is
 * really created on the client, Ref and {@code Fn::GetAtt ManagedLoginBrandingId} carry its id, an
 * update changes it in place, and deleting the stack removes it.
 */
@QuarkusTest
class CloudFormationCognitoManagedLoginBrandingIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String CLOUD_CONTROL_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser(COGNITO_CONTENT_TYPE, Parser.JSON);
        RestAssured.registerParser(CLOUD_CONTROL_CONTENT_TYPE, Parser.JSON);
    }

    @Test
    void stackCreatesUpdatesAndDeletesTheBrandingStyle() {
        String stackName = "cfn-cognito-branding-" + Long.toString(System.nanoTime(), 36);

        stack("CreateStack", stackName, template("LIGHT"));
        CfnStackWaits.StackState created = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());

        Map<String, String> outputs = outputs(stackName);
        String poolId = outputs.get("PoolId");
        String clientId = outputs.get("ClientId");
        String brandingId = outputs.get("BrandingRef");
        assertTrue(brandingId.matches(UUID_PATTERN), "Ref should be the style id, got " + brandingId);
        // GetAtt resolves to the same id rather than the literal "Branding.ManagedLoginBrandingId".
        assertEquals(brandingId, outputs.get("BrandingId"));

        cognito("DescribeManagedLoginBrandingByClient",
                "{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
            .statusCode(200)
            .body("ManagedLoginBranding.ManagedLoginBrandingId", equalTo(brandingId))
            .body("ManagedLoginBranding.UseCognitoProvidedValues", equalTo(false))
            .body("ManagedLoginBranding.Settings.categories.global.colorSchemeMode", equalTo("LIGHT"))
            .body("ManagedLoginBranding.Assets", hasSize(1))
            .body("ManagedLoginBranding.Assets[0].Category", equalTo("PAGE_FOOTER_BACKGROUND"));

        stack("UpdateStack", stackName, template("DARK"));
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        assertEquals(brandingId, outputs(stackName).get("BrandingRef"));

        describeBranding(poolId, brandingId)
            .statusCode(200)
            .body("ManagedLoginBranding.Settings.categories.global.colorSchemeMode", equalTo("DARK"));

        stack("DeleteStack", stackName, null);
        CfnStackWaits.awaitStackDeleted(stackName);

        describeBranding(poolId, brandingId)
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void cloudControlCreateResourceProvisionsTheBrandingStyle() throws InterruptedException {
        String poolId = cognito("CreateUserPool", "{\"PoolName\": \"cc-branding-pool\"}")
            .statusCode(200).extract().path("UserPool.Id");
        String clientId = cognito("CreateUserPoolClient",
                "{\"UserPoolId\": \"" + poolId + "\", \"ClientName\": \"cc-branding-client\"}")
            .statusCode(200).extract().path("UserPoolClient.ClientId");

        String desiredState = "{\\\"UserPoolId\\\":\\\"" + poolId + "\\\",\\\"ClientId\\\":\\\"" + clientId
                + "\\\",\\\"UseCognitoProvidedValues\\\":true}";
        String token = cloudControl("CreateResource",
                "{\"TypeName\":\"AWS::Cognito::ManagedLoginBranding\",\"DesiredState\":\"" + desiredState + "\"}")
            .path("ProgressEvent.RequestToken");

        String identifier = null;
        String status = null;
        for (int i = 0; i < 50 && identifier == null; i++) {
            ExtractableResponse<Response> event = cloudControl("GetResourceRequestStatus",
                    "{\"RequestToken\":\"" + token + "\"}");
            status = event.path("ProgressEvent.OperationStatus");
            if ("SUCCESS".equals(status)) {
                identifier = event.path("ProgressEvent.Identifier");
            } else if ("FAILED".equals(status)) {
                break;
            } else {
                Thread.sleep(100);
            }
        }
        assertNotNull(identifier, "CreateResource ended " + status);
        assertTrue(identifier.matches(UUID_PATTERN), "identifier should be the style id, got " + identifier);

        describeBranding(poolId, identifier)
            .statusCode(200)
            .body("ManagedLoginBranding.UseCognitoProvidedValues", equalTo(true));

        cloudControl("DeleteResource",
                "{\"TypeName\":\"AWS::Cognito::ManagedLoginBranding\",\"Identifier\":\"" + identifier + "\"}");

        describeBranding(poolId, identifier)
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    private io.restassured.response.ValidatableResponse describeBranding(String poolId, String brandingId) {
        return cognito("DescribeManagedLoginBranding",
                "{\"UserPoolId\": \"" + poolId + "\", \"ManagedLoginBrandingId\": \"" + brandingId + "\"}");
    }

    private void stack(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private Map<String, String> outputs(String stackName) {
        String xml = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }

    private io.restassured.response.ValidatableResponse cognito(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs(COGNITO_CONTENT_TYPE, ContentType.TEXT)))
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService." + target)
            .contentType(COGNITO_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private ExtractableResponse<Response> cloudControl(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs(CLOUD_CONTROL_CONTENT_TYPE, ContentType.TEXT)))
            .header("X-Amz-Target", "CloudApiService." + target)
            .contentType(CLOUD_CONTROL_CONTENT_TYPE)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract();
    }

    private static String template(String colorScheme) {
        return """
                {
                  "Resources": {
                    "Pool": {
                      "Type": "AWS::Cognito::UserPool",
                      "Properties": { "UserPoolName": "cfn-branding-pool" }
                    },
                    "Client": {
                      "Type": "AWS::Cognito::UserPoolClient",
                      "Properties": { "UserPoolId": { "Ref": "Pool" }, "ClientName": "cfn-branding-client" }
                    },
                    "Branding": {
                      "Type": "AWS::Cognito::ManagedLoginBranding",
                      "Properties": {
                        "UserPoolId": { "Ref": "Pool" },
                        "ClientId": { "Ref": "Client" },
                        "ReturnMergedResources": false,
                        "Settings": {
                          "categories": { "global": { "colorSchemeMode": "%s" } }
                        },
                        "Assets": [
                          {
                            "Category": "PAGE_FOOTER_BACKGROUND",
                            "ColorMode": "DARK",
                            "Extension": "SVG",
                            "Bytes": "PHN2Zy8+"
                          }
                        ]
                      }
                    }
                  },
                  "Outputs": {
                    "PoolId": { "Value": { "Ref": "Pool" } },
                    "ClientId": { "Value": { "Ref": "Client" } },
                    "BrandingRef": { "Value": { "Ref": "Branding" } },
                    "BrandingId": { "Value": { "Fn::GetAtt": ["Branding", "ManagedLoginBrandingId"] } }
                  }
                }
                """.formatted(colorScheme);
    }
}
