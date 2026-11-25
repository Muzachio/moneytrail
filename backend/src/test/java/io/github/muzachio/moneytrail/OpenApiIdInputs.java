package io.github.muzachio.moneytrail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Every place in {@code api/openapi.yaml} where a request names a resource by id: a path or query
 * parameter, or a request body property, whose schema is {@code format: uuid}. The cross-tenant suite
 * needs a case for each one, so an endpoint added to the spec with an id fails the build until it
 * has one.
 */
final class OpenApiIdInputs {

    private static final Set<String> METHODS = Set.of("get", "put", "post", "patch", "delete");

    /**
     * One id a request carries, such as {@code createTransaction body.accountId}. {@code nestedPath}
     * is true for a path id under another path id, such as {@code /v1/groups/{id}/expenses/{expenseId}}:
     * there an id of one parent must also be refused under another parent's path.
     */
    record IdInput(String operationId, String location, boolean nestedPath) implements Comparable<IdInput> {

        String key() {
            return operationId + " " + location;
        }

        @Override
        public int compareTo(IdInput other) {
            return key().compareTo(other.key());
        }
    }

    private final Map<String, Object> spec;

    private OpenApiIdInputs(Map<String, Object> spec) {
        this.spec = spec;
    }

    static Set<IdInput> of(Path specFile) throws IOException {
        return parse(Files.readString(specFile));
    }

    static Set<IdInput> parse(String yaml) {
        Map<String, Object> spec = new Yaml().load(yaml);
        return new OpenApiIdInputs(spec).idInputs();
    }

    private Set<IdInput> idInputs() {
        Set<IdInput> inputs = new TreeSet<>();
        map(spec.get("paths")).forEach((path, item) -> {
            Map<String, Object> pathItem = map(item);
            List<Object> shared = list(pathItem.get("parameters"));
            pathItem.forEach((method, value) -> {
                if (!METHODS.contains(method)) {
                    return;
                }
                Map<String, Object> operation = map(value);
                String operationId = String.valueOf(operation.get("operationId"));
                List<Object> parameters = new ArrayList<>(shared);
                parameters.addAll(list(operation.get("parameters")));
                List<String> pathIds = new ArrayList<>();
                for (Object parameter : parameters) {
                    Map<String, Object> resolved = resolve(map(parameter));
                    if (isUuid(map(resolved.get("schema")))) {
                        String in = String.valueOf(resolved.get("in"));
                        String name = String.valueOf(resolved.get("name"));
                        if (in.equals("path")) {
                            pathIds.add(name);
                        }
                        inputs.add(new IdInput(operationId, in + "." + name, false));
                    }
                }
                if (pathIds.size() > 1) {
                    // Every path id after the first is a child of the one before it.
                    for (String child : pathIds.subList(1, pathIds.size())) {
                        inputs.remove(new IdInput(operationId, "path." + child, false));
                        inputs.add(new IdInput(operationId, "path." + child, true));
                    }
                }
                Map<String, Object> body = map(
                        map(map(operation.get("requestBody")).get("content")).get("application/json"));
                if (!body.isEmpty()) {
                    bodyIds(map(body.get("schema")), "body", new HashSet<>())
                            .forEach(location -> inputs.add(new IdInput(operationId, location, false)));
                }
            });
        });
        return inputs;
    }

    private List<String> bodyIds(Map<String, Object> schema, String prefix, Set<String> seen) {
        Object ref = schema.get("$ref");
        if (ref != null && !seen.add(String.valueOf(ref))) {
            return List.of();
        }
        Map<String, Object> resolved = resolve(schema);
        List<String> ids = new ArrayList<>();
        map(resolved.get("properties")).forEach((name, property) -> {
            Map<String, Object> propertySchema = map(property);
            if (Boolean.TRUE.equals(resolve(propertySchema).get("readOnly"))) {
                return;
            }
            String location = prefix + "." + name;
            Map<String, Object> items = map(resolve(propertySchema).get("items"));
            if (!items.isEmpty()) {
                // An array, such as the members of a split: each of its elements carries the id.
                propertySchema = items;
                location += "[]";
            }
            if (isUuid(propertySchema)) {
                ids.add(location);
            } else {
                ids.addAll(bodyIds(propertySchema, location, new HashSet<>(seen)));
            }
        });
        return ids;
    }

    private boolean isUuid(Map<String, Object> schema) {
        return "uuid".equals(resolve(schema).get("format"));
    }

    /** Follows a local {@code $ref}, such as {@code #/components/schemas/Money}, to what it names. */
    private Map<String, Object> resolve(Map<String, Object> node) {
        Object ref = node.get("$ref");
        if (ref == null) {
            return node;
        }
        @Nullable Object target = spec;
        for (String part : String.valueOf(ref).replaceFirst("^#/", "").split("/", -1)) {
            target = map(target).get(part);
        }
        return resolve(map(target));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(@Nullable Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
}
