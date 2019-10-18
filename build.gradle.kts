plugins {
    java
    `maven-publish`
}

group = "com.loggi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("org.projectlombok:lombok:1.18.10")

    compileOnly("org.projectlombok:lombok:1.18.10")
    compileOnly("org.slf4j:slf4j-api:1.7.26")
    compileOnly("io.grpc:grpc-core:1.19.0")
    compileOnly("io.micrometer:micrometer-core:1.2.2")

    testCompile("junit", "junit", "4.12")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.loggi"
            artifactId = "grpc-metrics"
            version = "1.0"

            from(components["java"])
        }
    }
}