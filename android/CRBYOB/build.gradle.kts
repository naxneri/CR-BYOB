import java.util.Properties
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

plugins {
    id("com.android.application")
}

// Release signing: env vars (CI) or android/keystore.properties (local, git-ignored).
//   CRBYOB_KEYSTORE=/path/release.jks  CRBYOB_KEYSTORE_PASSWORD=...  CRBYOB_KEY_ALIAS=...  CRBYOB_KEY_PASSWORD=...
// Without them, release builds come out unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)

val releaseStore = signingValue("CRBYOB_KEYSTORE", "storeFile")

// ---------------------------------------------------------------------------------------------
// Version from the git tag. Release commits are tagged vMAJOR.MINOR.PATCH, optionally with a
// pre-release suffix -alpha.N / -beta.N / -rc.N (N = 0..29).
//
//   versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + STAGE
//   STAGE       = alpha.N -> N,  beta.N -> 30+N,  rc.N -> 60+N,  final release -> 99
//
// so v1.2.3 = 1020399 and v1.3.0-beta.2 = 1030032: every pre-release of 1.3.0 sorts above 1.2.x
// and below 1.3.0 itself (Play and Android only ever update to a higher versionCode).
// Builds from an untagged commit or a modified tree are named e.g. 1.2.3-dev.4+abc1234.dirty and
// keep the last tag's versionCode. With no tag at all: 0.0.0-dev+abc1234, versionCode 1.
// The update checker in the app computes the same versionCode from a GitHub tag (step 4).
// CI must check out with full history and tags (actions/checkout fetch-depth: 0).
// ---------------------------------------------------------------------------------------------
data class GitVersion(val name: String, val code: Int, val commit: String)

fun gitVersion(): GitVersion {
    // Only tags shaped vX.Y.Z… are considered (git glob: "v1.0" or "v2-test" are skipped, not misread).
    // No git on PATH, or not a checkout: fall back to a dev version instead of failing the build.
    val describe = try {
        providers.exec {
            commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--long", "--dirty",
                "--always", "--abbrev=7")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        logger.warn("git describe failed (${e.message}); building as 0.0.0-dev")
        ""
    }

    val tagged = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?-(\d+)-g([0-9a-f]+)(-dirty)?$""")
        .matchEntire(describe)
    if (tagged == null) {
        // a vX.Y.Z… tag that isn't a valid release tag (e.g. v1.2.3-preview): say so rather than build 0.0.0
        if (describe.startsWith("v"))
            throw GradleException("Nearest tag in '$describe' is not vX.Y.Z or vX.Y.Z-alpha|beta|rc.N")
        val hash = Regex("""^([0-9a-f]+)(-dirty)?$""").matchEntire(describe)
            ?: return GitVersion("0.0.0-dev", 1, "unknown")              // not a git checkout
        val (sha, dirty) = hash.destructured
        return GitVersion("0.0.0-dev+$sha" + (if (dirty.isEmpty()) "" else ".dirty"), 1, sha)
    }
    val (maj, min, pat, stage, n, ahead, sha, dirty) = tagged.destructured
    // toLongOrNull: an absurdly long number gives the range error below, not a NumberFormatException
    val major = maj.toLongOrNull() ?: Long.MAX_VALUE
    val minor = min.toLongOrNull() ?: Long.MAX_VALUE
    val patch = pat.toLongOrNull() ?: Long.MAX_VALUE
    if (minor > 99 || patch > 99 || major > 2099)
        throw GradleException("Tag v$maj.$min.$pat: minor/patch must be 0..99 and major 0..2099")
    val stageCode = if (stage.isEmpty()) 99 else {
        val num = n.toLongOrNull() ?: Long.MAX_VALUE
        if (num > 29) throw GradleException("Tag pre-release number must be 0..29 (got $stage.$n)")
        when (stage) { "alpha" -> num.toInt(); "beta" -> 30 + num.toInt(); else -> 60 + num.toInt() }
    }
    val base = "$major.$minor.$patch" + (if (stage.isEmpty()) "" else "-$stage.$n")
    val exact = ahead == "0" && dirty.isEmpty()
    val name = if (exact) base
        else "$base-dev.$ahead+$sha" + (if (dirty.isEmpty()) "" else ".dirty")
    return GitVersion(name, (major * 1_000_000 + minor * 10_000 + patch * 100 + stageCode).toInt(), sha)
}

val appVersion = gitVersion()

android {
    namespace = "com.crbyob.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crbyob.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name
        buildConfigField("String", "GIT_COMMIT", "\"${appVersion.commit}\"")
        // both flavors: links to the docs (the github flavor also uses crbyob.githubRepo for its update check)
        buildConfigField("String", "REPO_URL", "\"https://github.com/${providers.gradleProperty("crbyob.githubRepo").get()}\"")
        buildConfigField("String", "REPORT_WORKER_URL", "\"${providers.gradleProperty("crbyob.reportWorker").orElse("").get()}\"")
        manifestPlaceholders["cleartext"] = "false"
    }

    // github: sideloaded from GitHub Releases (self-update allowed).
    // play:   Google Play (updates only through Play).
    // Same applicationId and signing key, so users can move between channels.
    flavorDimensions += "channel"
    productFlavors {
        create("github") {
            dimension = "channel"
            buildConfigField("String", "GITHUB_REPO", "\"${providers.gradleProperty("crbyob.githubRepo").get()}\"")
            buildConfigField("String", "DONATE_URL", "\"${providers.gradleProperty("crbyob.donateUrl").get()}\"")
        }
        create("play") { dimension = "channel" }
    }

    // Keep the pre-Gradle layout: manifest, src/, res/, assets/ at the module root. Flavor-only code lives in
    // flavors/<name>/java (the default src/<flavor>/ would sit inside the main source root). models/scanners.json
    // and CHANGELOG.md (repo root) are added as assets by bundleSharedAssets below.
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
        }
        getByName("github") {
            manifest.srcFile("flavors/github/AndroidManifest.xml")   // merged over the main one: self-update bits
            java.setSrcDirs(listOf("flavors/github/java"))
            res.setSrcDirs(listOf("flavors/github/res"))
        }
        getByName("play") { java.setSrcDirs(listOf("flavors/play/java")) }
        getByName("test") { java.setSrcDirs(listOf("test")) }
    }

    signingConfigs {
        getByName("debug") {
            // The repo-local debug key the old build.sh used, so debug builds install over existing ones.
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signingValue("CRBYOB_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("CRBYOB_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("CRBYOB_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        // debug only: plain http, so a fake GitHub API on the PC can serve test updates (docs/building.md)
        getByName("debug") { manifestPlaceholders["cleartext"] = "true" }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")   // real org.json for JVM tests (android.jar only has stubs)
}

// ---------------------------------------------------------------------------------------------
// Files shared with the rest of the repo, bundled as assets:
//   models/scanners.json       -> assets/scanners.json        (validated as JSON)
//   models/mirror-protocol.json -> assets/mirror-protocol.json (validated as JSON; mirror viewer labels)
//   CHANGELOG.md         -> assets/CHANGELOG.md   ("What's new" in the app)
// A release build (exact tag) fails unless CHANGELOG.md has a dated "## [X.Y.Z] - YYYY-MM-DD" section.
// ---------------------------------------------------------------------------------------------
abstract class BundleSharedAssets : DefaultTask() {
    @get:InputFile abstract val scannerModels: RegularFileProperty
    @get:InputFile abstract val mirrorProtocol: RegularFileProperty
    @get:InputFile abstract val changelog: RegularFileProperty
    @get:Input abstract val releaseVersion: Property<String>   // "" for dev builds
    // User docs read in the app (DocsActivity): repo paths, kept in step with Docs.BUNDLED (DocsTest checks)
    @get:Input abstract val bundledDocs: ListProperty<String>
    @get:InputFile abstract val readme: RegularFileProperty       // repo root README.md (also fixes the root)
    @get:InputDirectory abstract val docsDir: DirectoryProperty    // docs/ (the .md files and their images)
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val models = scannerModels.get().asFile
        try {
            groovy.json.JsonSlurper().parse(models)
        } catch (e: Exception) {
            throw GradleException("${models.path} is not valid JSON: ${e.message}")
        }
        models.copyTo(File(out, "scanners.json"))

        val protocol = mirrorProtocol.get().asFile
        try {
            groovy.json.JsonSlurper().parse(protocol)
        } catch (e: Exception) {
            throw GradleException("${protocol.path} is not valid JSON: ${e.message}")
        }
        protocol.copyTo(File(out, "mirror-protocol.json"))

        val log = changelog.get().asFile
        val version = releaseVersion.get()
        if (version.isNotEmpty()) {
            val heading = Regex("""^## \[${Regex.escape(version)}\] - \d{4}-\d{2}-\d{2}\s*$""", RegexOption.MULTILINE)
            if (!heading.containsMatchIn(log.readText()))
                throw GradleException("CHANGELOG.md needs a '## [$version] - YYYY-MM-DD' section (with the release date) for v$version")
        }
        log.copyTo(File(out, "CHANGELOG.md"))

        // docs -> assets/doc/<repo path>, with every local image they show. Images wider than a phone screen
        // (1080 px) or over 150 KB are scaled to 1080 px wide and stored as JPEG under the same name (BitmapFactory
        // reads the content, not the extension): ~3 MB of PNG screenshots become ~1.4 MB.
        val root = readme.get().asFile.parentFile
        val docOut = File(out, "doc")
        val imgTag = Regex("""<img\s[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        val mdImg = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")
        val images = sortedSetOf<String>()
        for (rel in bundledDocs.get()) {
            val src = File(root, rel)
            if (!src.isFile) throw GradleException("bundled doc $rel is missing")
            src.copyTo(File(docOut, rel))
            val text = src.readText()
            for (m in imgTag.findAll(text) + mdImg.findAll(text)) {
                val href = m.groupValues[1]
                if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(href)) continue   // remote (badges)
                val f = File(src.parentFile, href).canonicalFile
                if (!f.isFile) throw GradleException("$rel shows $href, which doesn't exist")
                images += f.relativeTo(root.canonicalFile).invariantSeparatorsPath
            }
        }
        for (rel in images) {
            val src = File(root, rel)
            val dst = File(docOut, rel)
            dst.parentFile.mkdirs()
            val img = ImageIO.read(src) ?: throw GradleException("can't read image $rel")
            if (img.width <= 1080 && src.length() <= 150_000) { src.copyTo(dst); continue }
            val w = minOf(img.width, 1080)
            val h = Math.round(img.height * w.toDouble() / img.width).toInt()
            val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = rgb.createGraphics()
            g.color = Color(0x0E, 0x11, 0x16)                 // the app's background under any alpha
            g.fillRect(0, 0, w, h)
            g.drawImage(img.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null)
            g.dispose()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = 0.8f
            ImageIO.createImageOutputStream(dst).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(rgb, null, null), param)
            }
            writer.dispose()
        }
    }
}

val bundleSharedAssets = tasks.register<BundleSharedAssets>("bundleSharedAssets") {
    scannerModels.set(rootProject.file("../models/scanners.json"))
    mirrorProtocol.set(rootProject.file("../models/mirror-protocol.json"))
    changelog.set(rootProject.file("../CHANGELOG.md"))
    bundledDocs.set(listOf("README.md", "docs/README.md", "docs/using-the-app.md", "docs/phones.md"))
    readme.set(rootProject.file("../README.md"))
    docsDir.set(rootProject.file("../docs"))
    releaseVersion.set(if (appVersion.name.contains("-dev")) "" else appVersion.name)
    outputDir.set(layout.buildDirectory.dir("generated/sharedAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleSharedAssets, BundleSharedAssets::outputDir)
    }
}

// `./gradlew -q :CRBYOB:printVersion` -> key=value lines (CI appends them to $GITHUB_OUTPUT).
tasks.register("printVersion") {
    val name = appVersion.name
    val code = appVersion.code
    val commit = appVersion.commit
    doLast {
        println("versionName=$name")
        println("versionCode=$code")
        println("commit=$commit")
    }
}
