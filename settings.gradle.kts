pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // OsmAnd binaries (Ivy) - come nel sample ufficiale
        ivy {
            name = "OsmAndBinariesIvy"
            url = uri("https://builder.osmand.net")
            patternLayout {
                artifact("ivy/[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]")
            }
            metadataSources { artifact() } // è ivy, non maven
        }

        // spesso richiesto da dipendenze del mondo OsmAnd
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Bike4City Hub"
include(":app")
