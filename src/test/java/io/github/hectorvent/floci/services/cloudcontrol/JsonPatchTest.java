package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode apply(String target, String patch) throws Exception {
        return JsonPatch.apply(MAPPER.readTree(target), MAPPER.readTree(patch));
    }

    private static void assertPatched(String expected, String target, String patch) throws Exception {
        assertEquals(MAPPER.readTree(expected), apply(target, patch));
    }

    @Test
    void appliesEveryOperationInOrder() throws Exception {
        assertPatched("{\"a\":2,\"tags\":[\"x\",\"y\",\"z\"],\"c\":{\"d\":1},\"e\":{\"d\":1}}",
                "{\"a\":1,\"b\":true,\"tags\":[\"x\",\"z\"],\"c\":{\"d\":1}}",
                "[{\"op\":\"replace\",\"path\":\"/a\",\"value\":2},"
                        + "{\"op\":\"remove\",\"path\":\"/b\"},"
                        + "{\"op\":\"add\",\"path\":\"/tags/1\",\"value\":\"y\"},"
                        + "{\"op\":\"copy\",\"from\":\"/c\",\"path\":\"/e\"},"
                        + "{\"op\":\"test\",\"path\":\"/e/d\",\"value\":1.0}]");
    }

    @Test
    void appendsWithDashAndMovesValues() throws Exception {
        assertPatched("{\"list\":[1,2],\"to\":\"v\"}",
                "{\"list\":[1],\"from\":\"v\"}",
                "[{\"op\":\"add\",\"path\":\"/list/-\",\"value\":2},"
                        + "{\"op\":\"move\",\"from\":\"/from\",\"path\":\"/to\"}]");
    }

    @Test
    void readsEscapedTokensAndPathsWithoutALeadingSlash() throws Exception {
        assertPatched("{\"a/b\":1,\"m~n\":2,\"Plain\":3}",
                "{\"a/b\":0,\"m~n\":0,\"Plain\":0}",
                "[{\"op\":\"replace\",\"path\":\"/a~1b\",\"value\":1},"
                        + "{\"op\":\"replace\",\"path\":\"/m~0n\",\"value\":2},"
                        + "{\"op\":\"replace\",\"path\":\"Plain\",\"value\":3}]");
    }

    @Test
    void rejectsInvalidOperationsWithoutTouchingTheTarget() throws Exception {
        JsonNode target = MAPPER.readTree("{\"a\":1,\"list\":[1]}");
        for (String patch : new String[] {
                "{\"op\":\"add\",\"path\":\"/a\",\"value\":1}",
                "[{\"op\":\"add\",\"path\":\"/a\"}]",
                "[{\"op\":\"remove\",\"path\":\"/missing\"}]",
                "[{\"op\":\"add\",\"path\":\"/list/5\",\"value\":1}]",
                "[{\"op\":\"add\",\"path\":\"/missing/child\",\"value\":1}]",
                "[{\"op\":\"move\",\"from\":\"/list\",\"path\":\"/list/0\"}]",
                "[{\"op\":\"replace\",\"path\":\"/a\",\"value\":2},{\"op\":\"test\",\"path\":\"/a\",\"value\":1}]"}) {
            assertThrows(JsonPatch.InvalidPatchException.class,
                    () -> JsonPatch.apply(target, MAPPER.readTree(patch)), patch);
        }
        assertEquals(MAPPER.readTree("{\"a\":1,\"list\":[1]}"), target);
    }
}
