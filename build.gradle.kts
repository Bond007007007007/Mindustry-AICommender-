plugins{
    java
}

sourceSets.main{
    java.setSrcDirs(listOf("src"))
}

// Mindustry version to depend on.
// Valid values:
// - latest: depend on the latest release of mindustry
// - be: depend on the very latest commit of mindustry
// - v<number>: depend on a specific version
val mindustryVersion = "v160"
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val projectName = project.name

repositories{
    mavenCentral()

    //Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
    ivy{
        url = uri("https://github.com/")
        patternLayout{
            val pattern = when(mindustryVersion){
                "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar" //latest stable release
                "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar" //latest commit (BE)
                else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar" //specific release
            }
            val path = pattern.substring(0, pattern.lastIndexOf('/'))

            artifact("$path/[classifier].jar")
            artifact(pattern.replaceFirst(Regex("""\.jar$"""), "(-[classifier]).jar"))
        }
        metadataSources{ artifact() }

        content{
            if(mindustryVersion == "be"){
                //BE artifact version is always 'latest'
                includeVersion("Anuken", "MindustryBuilds", "latest")
            }else{
                includeVersion("Anuken", "Mindustry", mindustryVersion)
            }
        }
    }
}

java{
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies{
    compileOnly(if(mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
}

tasks.jar{
    archiveFileName.set("${projectName}Desktop.jar")

    from(configurations.runtimeClasspath.map{ files ->
        files.map{ if(it.isDirectory) it else zipTree(it) }
    })

    from(projectDir){
        include("mod.hjson")
        include("icon.png")
    }

    from("assets/"){
        include("**")
    }
}

val jarAndroid = tasks.register("jarAndroid"){
    dependsOn(tasks.jar)

    doLast{
        // Resolve android.jar: ANDROID_JAR override, then the Android SDK platforms dir.
        val androidJar = System.getenv("ANDROID_JAR")?.let{ File(it) } ?: run{
            val sdk = sdkRoot?.let{ File(it) }
            if(sdk == null || !sdk.exists()){
                throw GradleException("No valid Android SDK found. Set ANDROID_HOME or ANDROID_JAR.")
            }

            val platformRoot = File(sdk, "platforms").listFiles()
                ?.sortedDescending()
                ?.firstOrNull{ File(it, "android.jar").exists() }
                ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")
            File(platformRoot, "android.jar")
        }

        // Prefer a d8 on PATH, otherwise run D8 from an R8 jar via the current JDK.
        val d8OnPath = System.getenv("PATH")?.split(File.pathSeparator)
            ?.map{ File(it, if(isWindows) "d8.bat" else "d8") }
            ?.firstOrNull{ it.canExecute() }
        val r8Jar = System.getenv("R8_JAR")?.let{ File(it) }
        val javaBin = File(System.getProperty("java.home"), if(isWindows) "bin/java.exe" else "bin/java")

        val classpath = (configurations.compileClasspath.get().files +
                configurations.runtimeClasspath.get().files)

        val command = buildList{
            when{
                d8OnPath != null -> add(d8OnPath.path)
                r8Jar != null && r8Jar.exists() -> addAll(listOf(javaBin.path, "-cp", r8Jar.path, "com.android.tools.r8.D8"))
                else -> throw GradleException("No d8 found. Add d8 to PATH or set R8_JAR to an r8.jar.")
            }
            add("--lib")
            add(androidJar.path)
            classpath.forEach{ addAll(listOf("--classpath", it.path)) }
            addAll(listOf("--min-api", "21"))
            addAll(listOf("--output", "${projectName}Android.jar"))
            add("${projectName}Desktop.jar")
        }

        // Dex and desugar files; requires d8 (or R8's D8 entry point).
        val process = ProcessBuilder(command)
            .directory(layout.buildDirectory.dir("libs").get().asFile)
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { println("[d8] $it") }
        }

        val exitCode = process.waitFor()

        if(exitCode != 0) throw GradleException("d8 failed with exit code $exitCode")
    }
}

tasks.register<Jar>("deploy"){
    dependsOn(jarAndroid, tasks.jar)
    archiveFileName.set("$projectName.jar")

    val libs = layout.buildDirectory.dir("libs")
    from(provider{
        listOf(
            zipTree(libs.get().file("${projectName}Desktop.jar")),
            zipTree(libs.get().file("${projectName}Android.jar"))
        )
    })

    doLast{
        libs.get().file("${projectName}Android.jar").asFile.delete()
    }
}