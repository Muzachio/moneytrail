import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.openapi.generator)
}

group = "io.github.muzachio"
version = "0.0.1-SNAPSHOT"
description = "MoneyTrail backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.jspecify)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.docker.compose)

    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc.test)
    testImplementation(libs.spring.boot.starter.flyway.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Spec-first API (ADR-0006): the Spring interfaces are generated from api/openapi.yaml on every
// build and never committed, so they cannot drift from the spec.
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("../api/openapi.yaml")
    outputDir = generatedOpenApiDir
    // Inside the open `shared` module, so every module may implement the interfaces without
    // Spring Modulith seeing a separate module.
    apiPackage = "io.github.muzachio.moneytrail.shared.api"
    modelPackage = "io.github.muzachio.moneytrail.shared.api.model"
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot4" to "true",
        "useTags" to "true",
        "skipDefaultInterface" to "true",
        "useResponseEntity" to "true",
        "openApiNullable" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        // Without it every build writes a new date into the sources and recompiles them.
        "hideGenerationTimestamp" to "true",
    )
}

sourceSets {
    main {
        java.srcDir(generatedOpenApiDir.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        // Null-safety is enforced at compile time for packages annotated with @NullMarked,
        // so a missing null check fails the build instead of failing in production.
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Locally the app only listens on this machine: no firewall prompt and nothing exposed on the
// network. Only bootRun is affected; the production jar keeps listening on all interfaces.
tasks.bootRun {
    systemProperty("server.address", "127.0.0.1")
}

spotless {
    java {
        // Only hand-written code; generated sources follow the generator's own style.
        target("src/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        formatAnnotations()
    }
}
