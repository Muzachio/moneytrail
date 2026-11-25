package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.OpenApiIdInputs.IdInput;
import org.junit.jupiter.api.Test;

/**
 * What {@link OpenApiIdInputs} finds in a spec, on a small one made for it: ids in a path (shared by
 * the path item or the operation's own), in a query and in a body (nested, and through {@code $ref}),
 * and a child id under its parent's path, which the cross-tenant suite then sends under another
 * parent (#88 adds the first: an expense under its group).
 */
class OpenApiIdInputsTests {

    private static final String SPEC = """
            openapi: 3.1.0
            paths:
              /v1/groups/{id}/expenses/{expenseId}:
                parameters:
                  - $ref: '#/components/parameters/GroupId'
                get:
                  operationId: getExpense
                  parameters:
                    - name: expenseId
                      in: path
                      required: true
                      schema: {type: string, format: uuid}
                    - name: limit
                      in: query
                      schema: {type: integer}
              /v1/things:
                get:
                  operationId: listThings
                  parameters:
                    - name: ownerId
                      in: query
                      schema: {type: string, format: uuid}
                post:
                  operationId: createThing
                  requestBody:
                    content:
                      application/json:
                        schema: {$ref: '#/components/schemas/NewThing'}
            components:
              parameters:
                GroupId:
                  name: id
                  in: path
                  required: true
                  schema: {type: string, format: uuid}
              schemas:
                Id: {type: string, format: uuid}
                NewThing:
                  type: object
                  properties:
                    name: {type: string}
                    accountId: {$ref: '#/components/schemas/Id'}
                    replacement: {$ref: '#/components/schemas/Draft'}
                    owner:
                      type: object
                      properties:
                        userId: {type: string, format: uuid}
                        createdBy: {type: string, format: uuid, readOnly: true}
                    members:
                      type: array
                      items:
                        type: object
                        properties:
                          userId: {type: string, format: uuid}
                          share: {type: integer}
                    tagIds:
                      type: array
                      items: {$ref: '#/components/schemas/Id'}
                Draft:
                  type: object
                  properties:
                    accountId: {type: string, format: uuid}
                    # A schema that refers to itself is read once, not forever.
                    next: {$ref: '#/components/schemas/Draft'}
            """;

    @Test
    void findsEveryIdARequestCarriesAndMarksChildIdsUnderTheirParent() {
        assertThat(OpenApiIdInputs.parse(SPEC))
                .containsExactly(
                        new IdInput("createThing", "body.accountId", false),
                        new IdInput("createThing", "body.members[].userId", false),
                        new IdInput("createThing", "body.owner.userId", false),
                        new IdInput("createThing", "body.replacement.accountId", false),
                        new IdInput("createThing", "body.tagIds[]", false),
                        new IdInput("getExpense", "path.expenseId", true),
                        new IdInput("getExpense", "path.id", false),
                        new IdInput("listThings", "query.ownerId", false));
    }
}
