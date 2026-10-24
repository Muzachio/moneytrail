# ADR-0003: Java 25 and Spring Boot for the backend

- **Status:** Accepted
- **Date:** 2026-10-14

## Context
Candidates were Java, Kotlin, Go and C#. The project should:
- use the stack most common in backend roles at banks, payment processors and large
  fintechs internationally,
- be easy to hand to future contributors,
- have mature libraries for security, persistence, testing and observability.

## Decision
**Java 25 (LTS)** with **Spring Boot 4**, built with Gradle (Kotlin DSL).
Modern language features are used where they help: records for value objects and
DTOs, sealed interfaces for domain results, pattern matching, and virtual threads for
request handling.

## Alternatives considered
- **Go:** excellent for services like this and fast to run, but a smaller share of
  finance job openings, and fewer batteries included (auth, sessions, modularity checks).
- **Kotlin + Spring:** pleasant and growing, but a smaller market than Java, and
  everything here carries over to Kotlin if needed.
- **C# / .NET:** strong in some banks, weaker in European fintech.

## Consequences
- Larger memory footprint than Go: the Fly.io machine needs about 512 MB–1 GB.
  GraalVM native image stays an option if cost or startup time matters.
- A mainstream stack, so documentation and examples are plentiful.
