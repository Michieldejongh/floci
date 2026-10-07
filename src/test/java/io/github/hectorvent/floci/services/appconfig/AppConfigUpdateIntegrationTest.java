package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class AppConfigUpdateIntegrationTest {

    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String create(String path, String body) {
        return given().contentType(ContentType.JSON).body(body)
                .when().post(path)
                .then().statusCode(201)
                .extract().path("Id");
    }

    @Test
    void updateApplicationChangesOnlyTheGivenFields() {
        String appId = create("/applications", "{\"Name\": \"update-app\", \"Description\": \"before\"}");

        given().contentType(ContentType.JSON).body("{\"Description\": \"after\"}")
                .when().patch("/applications/" + appId)
                .then().statusCode(200)
                .body("Id", equalTo(appId))
                .body("Name", equalTo("update-app"))
                .body("Description", equalTo("after"));
    }

    @Test
    void updateEnvironmentRenamesIt() {
        String appId = create("/applications", "{\"Name\": \"update-env-app\"}");
        String envId = create("/applications/" + appId + "/environments", "{\"Name\": \"before\"}");

        given().contentType(ContentType.JSON).body("{\"Name\": \"after\"}")
                .when().patch("/applications/" + appId + "/environments/" + envId)
                .then().statusCode(200)
                .body("Id", equalTo(envId))
                .body("Name", equalTo("after"));
    }

    @Test
    void configurationProfileKeepsAndUpdatesItsKmsKey() {
        String appId = create("/applications", "{\"Name\": \"update-profile-app\"}");
        String profileId = create("/applications/" + appId + "/configurationprofiles",
                "{\"Name\": \"profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\","
                        + " \"KmsKeyIdentifier\": \"alias/config\"}");

        given().when().get("/applications/" + appId + "/configurationprofiles/" + profileId)
                .then().statusCode(200)
                .body("KmsKeyIdentifier", equalTo("alias/config"))
                .body("KmsKeyArn", nullValue());

        given().contentType(ContentType.JSON).body("{\"KmsKeyIdentifier\": \"" + KEY_ARN + "\"}")
                .when().patch("/applications/" + appId + "/configurationprofiles/" + profileId)
                .then().statusCode(200)
                .body("Name", equalTo("profile"))
                .body("KmsKeyIdentifier", equalTo(KEY_ARN))
                .body("KmsKeyArn", equalTo(KEY_ARN));
    }

    @Test
    void updatingAnUnknownProfileIsNotFound() {
        String appId = create("/applications", "{\"Name\": \"update-missing-app\"}");

        given().contentType(ContentType.JSON).body("{\"Name\": \"x\"}")
                .when().patch("/applications/" + appId + "/configurationprofiles/nope123")
                .then().statusCode(404);
    }
}
