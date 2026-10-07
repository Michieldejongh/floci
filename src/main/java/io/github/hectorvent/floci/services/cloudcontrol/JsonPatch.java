package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * RFC 6902 JSON Patch, as Cloud Control {@code UpdateResource} applies a {@code PatchDocument} to a
 * resource's current model: every operation in order, each against the result of the previous one,
 * and the whole patch fails if any operation is invalid or a {@code test} does not hold.
 *
 * <p>Paths are RFC 6901 pointers. A path without the leading slash ({@code RetentionInDays}) is
 * read as relative to the root, which the Cloud Control user guide's own CLI example relies on.
 */
final class JsonPatch {

    private JsonPatch() {
    }

    /** Thrown for an invalid patch document or a failed {@code test}; nothing is applied then. */
    static final class InvalidPatchException extends RuntimeException {
        InvalidPatchException(String message) {
            super(message);
        }
    }

    /** Applies {@code patch} to a copy of {@code target} and returns the copy. */
    static JsonNode apply(JsonNode target, JsonNode patch) {
        if (patch == null || !patch.isArray()) {
            throw new InvalidPatchException("PatchDocument must be a JSON array of patch operations.");
        }
        JsonNode document = target.deepCopy();
        for (JsonNode operation : patch) {
            if (!operation.isObject()) {
                throw new InvalidPatchException("Each patch operation must be a JSON object.");
            }
            document = applyOperation(document, operation);
        }
        return document;
    }

    private static JsonNode applyOperation(JsonNode document, JsonNode operation) {
        String op = text(operation, "op");
        JsonPointer path = pointer(text(operation, "path"));
        return switch (op) {
            case "add" -> add(document, path, value(operation));
            case "remove" -> remove(document, path);
            case "replace" -> {
                requireExisting(document, path);
                yield add(remove(document, path), path, value(operation));
            }
            case "move" -> {
                JsonPointer from = pointer(text(operation, "from"));
                JsonNode moved = requireExisting(document, from);
                if (isProperPrefix(from, path)) {
                    throw new InvalidPatchException("Cannot move " + from + " into its own child " + path + ".");
                }
                yield add(remove(document, from), path, moved);
            }
            case "copy" -> {
                JsonPointer from = pointer(text(operation, "from"));
                yield add(document, path, requireExisting(document, from).deepCopy());
            }
            case "test" -> {
                JsonNode actual = requireExisting(document, path);
                if (!sameValue(actual, value(operation))) {
                    throw new InvalidPatchException("Patch test operation failed for path " + path + ".");
                }
                yield document;
            }
            default -> throw new InvalidPatchException("Unsupported patch operation '" + op + "'.");
        };
    }

    private static JsonNode add(JsonNode document, JsonPointer path, JsonNode value) {
        if (path.matches()) {
            return value.deepCopy();
        }
        JsonNode parent = document.at(path.head());
        String token = path.last().getMatchingProperty();
        if (parent instanceof ObjectNode object) {
            object.set(token, value.deepCopy());
        } else if (parent instanceof ArrayNode array) {
            int index = "-".equals(token) ? array.size() : arrayIndex(token, path);
            if (index > array.size()) {
                throw new InvalidPatchException("Array index out of bounds at path " + path + ".");
            }
            array.insert(index, value.deepCopy());
        } else {
            throw new InvalidPatchException("Path " + path + " has no parent to add to.");
        }
        return document;
    }

    private static JsonNode remove(JsonNode document, JsonPointer path) {
        requireExisting(document, path);
        if (path.matches()) {
            throw new InvalidPatchException("Cannot remove the whole resource model.");
        }
        JsonNode parent = document.at(path.head());
        String token = path.last().getMatchingProperty();
        if (parent instanceof ObjectNode object) {
            object.remove(token);
        } else if (parent instanceof ArrayNode array) {
            array.remove(arrayIndex(token, path));
        }
        return document;
    }

    private static JsonNode requireExisting(JsonNode document, JsonPointer path) {
        if (path.matches()) {
            return document;
        }
        JsonNode parent = document.at(path.head());
        String token = path.last().getMatchingProperty();
        JsonNode found = null;
        if (parent instanceof ObjectNode object) {
            found = object.get(token);
        } else if (parent instanceof ArrayNode array) {
            int index = arrayIndex(token, path);
            found = index < array.size() ? array.get(index) : null;
        }
        if (found == null) {
            throw new InvalidPatchException("Path " + path + " does not exist in the resource model.");
        }
        return found;
    }

    private static int arrayIndex(String token, JsonPointer path) {
        if (!token.matches("0|[1-9][0-9]{0,8}")) {
            throw new InvalidPatchException("Invalid array index '" + token + "' at path " + path + ".");
        }
        return Integer.parseInt(token);
    }

    private static boolean isProperPrefix(JsonPointer from, JsonPointer path) {
        String prefix = from.toString() + "/";
        return path.toString().startsWith(prefix);
    }

    /** RFC 6902 section 4.6: numbers compare by value, objects regardless of member order. */
    private static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = a.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode other = b.get(field.getKey());
                if (other == null || !sameValue(field.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameValue(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static String text(JsonNode operation, String field) {
        JsonNode node = operation.get(field);
        if (node == null || !node.isTextual()) {
            throw new InvalidPatchException("Patch operation is missing '" + field + "'.");
        }
        return node.asText();
    }

    private static JsonNode value(JsonNode operation) {
        JsonNode node = operation.get("value");
        if (node == null) {
            throw new InvalidPatchException("Patch operation '" + operation.path("op").asText()
                    + "' is missing 'value'.");
        }
        return node;
    }

    private static JsonPointer pointer(String path) {
        String normalized = path.isEmpty() || path.startsWith("/") ? path : "/" + path;
        try {
            return JsonPointer.compile(normalized);
        } catch (IllegalArgumentException e) {
            throw new InvalidPatchException("Invalid patch path '" + path + "'.");
        }
    }
}
