package io.github.hectorvent.floci.services.appsync.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppSyncDirectiveTest {

    @Test
    void cognitoUserPoolsGroupsAreOptionalAsOnAws() {
        assertEquals("directive @aws_cognito_user_pools(cognito_groups: [String]) on OBJECT | FIELD_DEFINITION",
                AppSyncDirective.AWS_COGNITO_USER_POOLS.sdl());
    }
}
