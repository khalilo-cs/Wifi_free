// WAVE-NET Android app — built WITHOUT the Android Gradle Plugin.
// Reason: dl.google.com / maven.google.com are not reachable in the build environment (see docs/ANDROID_ARCHITECTURE.md).
// Toolchain: Debian android.jar (API 23, AOSP) + aapt2 + dx (dalvik-exchange) + zipalign + apksigner, Kotlin from Maven Central.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

plugins { kotlin("jvm") }

val sdkDir = providers.environmentVariable("WAVENET_ANDROID_SDK").getOrElse("/usr/lib/android-sdk")
val androidJar = file("$sdkDir/platforms/android-23/android.jar")
val minSdk = 26      // dx needs API 26 for any invokedynamic; covers Android 8.0+
val targetSdk = 34
val versionName = "0.1.0"
val gen = layout.buildDirectory.dir("wave")

dependencies {
  compileOnly(files(androidJar))
  compileOnly(files(gen.map { it.dir("rclasses") }))
  testImplementation(kotlin("test"))
}

java { toolchain { } }
kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_1_8)
    // Keep bytecode dx-compatible: no invokedynamic for lambdas/SAM/string concatenation.
    freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class", "-Xstring-concat=inline", "-Xno-call-assertions",
                            "-Xno-param-assertions", "-Xno-receiver-assertions")
  }
}
tasks.withType<JavaCompile>().configureEach { sourceCompatibility = "1.8"; targetCompatibility = "1.8" }
tasks.test { useJUnitPlatform() }

fun run(vararg cmd: String) {
  val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
  val out = p.inputStream.bufferedReader().readText()
  if (p.waitFor() != 0) throw GradleException("command failed: ${cmd.joinToString(" ")}\n$out")
}

val aaptCompile by tasks.registering {
  inputs.dir("src/main/res"); outputs.file(gen.map { it.file("res.zip") })
  doLast {
    require(androidJar.exists()) { "android.jar not found at $androidJar (apt install android-sdk-platform-23)" }
    gen.get().asFile.mkdirs()
    run("aapt2", "compile", "--dir", file("src/main/res").path, "-o", gen.get().file("res.zip").asFile.path)
  }
}
val aaptLink by tasks.registering {
  dependsOn(aaptCompile)
  inputs.file("src/main/AndroidManifest.xml"); inputs.file(gen.map { it.file("res.zip") })
  outputs.file(gen.map { it.file("base.apk") }); outputs.dir(gen.map { it.dir("rjava") })
  doLast {
    val g = gen.get().asFile
    run("aapt2", "link", "-I", androidJar.path, "--manifest", file("src/main/AndroidManifest.xml").path,
        "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk", "--version-code", "1",
        "--version-name", versionName, "--java", File(g, "rjava").path, "--auto-add-overlay",
        "-o", File(g, "base.apk").path, File(g, "res.zip").path)
  }
}
val compileR by tasks.registering(JavaCompile::class) {
  dependsOn(aaptLink)
  source(gen.map { it.dir("rjava") })
  classpath = files(androidJar)
  destinationDirectory.set(gen.map { it.dir("rclasses") })
  options.compilerArgs.addAll(listOf("-Xlint:-options"))
}
tasks.named("compileKotlin") { dependsOn(compileR) }

val dex by tasks.registering {
  dependsOn("compileKotlin")
  val stdlib = configurations.runtimeClasspath
  inputs.files(stdlib); inputs.dir(layout.buildDirectory.dir("classes/kotlin/main")); inputs.dir(gen.map { it.dir("rclasses") })
  outputs.file(gen.map { it.file("dex/classes.dex") })
  doLast {
    val out = gen.get().dir("dex").asFile.also { it.mkdirs() }
    val classes = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
    // dx cannot read Java 9+ module descriptors (META-INF/versions/9/module-info.class): unpack libraries without META-INF.
    val libs = gen.get().dir("libclasses").asFile.also { it.deleteRecursively(); it.mkdirs() }
    for (jar in stdlib.get().files) {
      ZipFile(jar).use { z ->
        for (e in z.entries()) {
          if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/") || e.name.endsWith("module-info.class")) continue
          val f = File(libs, e.name).also { it.parentFile.mkdirs() }
          z.getInputStream(e).use { inp -> f.outputStream().use { inp.copyTo(it) } }
        }
      }
    }
    run("dalvik-exchange", "--dex", "--min-sdk-version=$minSdk", "--output=${File(out, "classes.dex")}",
        classes.path, gen.get().dir("rclasses").asFile.path, libs.path)
  }
}
val apk by tasks.registering {
  group = "build"; description = "Builds and signs build/outputs/wavenet-$versionName-debug.apk"
  dependsOn(dex)
  val outApk = layout.buildDirectory.file("outputs/wavenet-$versionName-debug.apk")
  dependsOn(aaptLink)
  inputs.file(gen.map { it.file("base.apk") }); inputs.file(gen.map { it.file("dex/classes.dex") })
  outputs.file(outApk)
  doLast {
    val g = gen.get().asFile
    val unsigned = File(g, "unsigned.apk"); val aligned = File(g, "aligned.apk")
    File(g, "base.apk").copyTo(unsigned, overwrite = true)
    run("zip", "-j", "-q", unsigned.path, File(g, "dex/classes.dex").path)
    run("zipalign", "-p", "-f", "4", unsigned.path, aligned.path)
    // Debug signing key is generated locally in build/ — never committed (no secrets in the repository).
    val ks = File(g, "debug.keystore")
    if (!ks.exists()) run("keytool", "-genkeypair", "-keystore", ks.path, "-storepass", "android", "-keypass", "android",
        "-alias", "wavenetdebug", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=WAVE-NET Debug")
    val o = outApk.get().asFile.also { it.parentFile.mkdirs() }
    run("apksigner", "sign", "--ks", ks.path, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--min-sdk-version", "$minSdk", "--out", o.path, aligned.path)
    run("apksigner", "verify", "--min-sdk-version", "$minSdk", o.path)
    println("APK: ${o.path}")
  }
}
