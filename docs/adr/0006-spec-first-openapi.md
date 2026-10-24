# ADR-0006: Spec-first API with OpenAPI

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
Backend and frontend live in the same repository and change together. Hand-written
TypeScript types drift from the Java DTOs, and generating the spec from annotations
makes the contract an afterthought.

## Decision
`api/openapi.yaml` (OpenAPI 3.1) is the source of truth:
- the backend generates Spring interfaces and DTOs with `openapi-generator`
  (`interfaceOnly`), and controllers implement them,
- the frontend generates types with `openapi-typescript` and calls the API through `openapi-fetch`,
- CI lints the spec (Redocly) and fails if generated code is out of date,
- API docs are published from the spec.

## Consequences
- A breaking change shows up as a compile error on both sides.
- The spec is reviewed like code, before the implementation.
- Small cost: generated code needs a build step.
