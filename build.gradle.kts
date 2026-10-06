plugins {
    java
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.tutorplatform"
version = "0.1.0-SNAPSHOT"

// Security patches newer than the Spring Boot 4.0.8 dependency BOM.
extra["tomcat.version"] = "11.0.25"
extra["jackson-bom.version"] = "3.1.7"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("sandbox-integration")
    }
}

tasks.register<Test>("sandboxIntegrationTest") {
    description = "Runs integration tests against the real Docker sandbox runtime."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    useJUnitPlatform {
        includeTags("sandbox-integration")
    }
    shouldRunAfter(tasks.test)
}
