plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    mavenCentral()
}

sourceSets.main {
    kotlin.srcDir(file("src"))
}

gradlePlugin {
    plugins {
        create("simplePlugin") {
            id = "dev.ilyapple.ghc.shared-variables"
            implementationClass = "dev.ilyapple.ghc.sharedvariables.NoOp"
        }
    }
}
