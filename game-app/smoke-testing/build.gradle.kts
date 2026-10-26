import de.undercouch.gradle.tasks.download.Download

plugins {
    id("triplea-java-library")
    alias(libs.plugins.download)
}

dependencies {
    implementation(project(":domain-data"))
    implementation(project(":game-core"))
    testImplementation(project(":game-headless"))
    testImplementation(project(":java-extras"))
    testImplementation(project(":test-common"))
}

tasks.named<Test>("test") {
    // AiGameTest is memory intensive due to ConcurrentBattleCalculator threads deserializing GameData concurrently.
    maxHeapSize = "2G"
}

// Gather all URLs in the same game list file
val downloadURLs = mutableListOf<String>()
val saveGamesFile = project.layout.projectDirectory.file("save-game-list.txt").asFile
saveGamesFile.readLines().forEachIndexed { _, path ->
    if (!path.startsWith("#")) {
        downloadURLs.add(path)
    }
}

// Create a download task for the URLs
val downloadTask = tasks.register<Download>("downloadSaveGames") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Downloads save game files"

    src(downloadURLs)
    dest(project.layout.buildDirectory.dir("downloads/save-games"))
    overwrite(false)
    onlyIfModified(true)
    quiet(true)
}

// And wire test resource processing as a dependant on the download task
tasks.named<ProcessResources>("processTestResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(downloadTask) {
        into("save-games")
    }
}

// AI-improvement harness: plays patched-vs-baseline Hard AI games.
// ./gradlew :smoke-testing:aiMatch --args="--map big_world_1942.xml --patched Axis --patches NO_OP --rounds 30 --swap"
tasks.register<JavaExec>("aiMatch") {
    group = "application"
    description = "Plays headless Hard AI games, one alliance patched, the rest baseline."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("games.strategy.triplea.ai.harness.AiMatch")
    workingDir = projectDir
    maxHeapSize = "3G"
}
