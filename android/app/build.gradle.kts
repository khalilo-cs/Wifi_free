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
val versionName = "0.3.0"
val gen = layout.buildDirectory.dir("wave")

dependencies {
  compileOnly(files(androidJar))
  compileOnly(files(gen.map { it.dir("rclasses") }))
  // Bouncy Castle (MIT-style licence): Ed25519 + TLS 1.3 with exporter on every Android version (min API 26).
  val bc = "1.80"
  implementation("org.bouncycastle:bcprov-jdk18on:$bc")
  implementation("org.bouncycastle:bctls-jdk18on:$bc")
  implementation("org.bouncycastle:bcpkix-jdk18on:$bc")
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
        "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk", "--version-code", "3",
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
  outputs.dir(gen.map { it.dir("dex") })
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
    checkNoInvokeDynamicReachable(listOf(classes, gen.get().dir("rclasses").asFile), libs)
    out.listFiles()?.forEach { it.delete() }
    // Bouncy Castle exceeds 65,536 method references: native multidex (API 21+) via dx --multi-dex.
    run("dalvik-exchange", "--dex", "--multi-dex", "--min-sdk-version=$minSdk", "--output=${out.path}",
        classes.path, gen.get().dir("rclasses").asFile.path, libs.path)
  }
}
val apk by tasks.registering {
  group = "build"; description = "Builds and signs build/outputs/wavenet-$versionName-debug.apk"
  dependsOn(dex)
  val outApk = layout.buildDirectory.file("outputs/wavenet-$versionName-debug.apk")
  dependsOn(aaptLink)
  inputs.file(gen.map { it.file("base.apk") }); inputs.dir(gen.map { it.dir("dex") })
  outputs.file(outApk)
  doLast {
    val g = gen.get().asFile
    val unsigned = File(g, "unsigned.apk"); val aligned = File(g, "aligned.apk")
    File(g, "base.apk").copyTo(unsigned, overwrite = true)
    val dexes = File(g, "dex").listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }!!.sortedBy { it.name }
    require(dexes.isNotEmpty()) { "no dex output" }
    run("zip", "-j", "-q", unsigned.path, *dexes.map { it.path }.toTypedArray())
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

/**
 * dx accepts invokedynamic, but Android cannot run LambdaMetafactory: such code crashes when executed.
 * Rules enforced at build time (exact constant-pool parsing, no heuristics):
 *  1. App classes must contain NO invokedynamic.
 *  2. Library classes may contain it only in the reviewed set below (kotlin-stdlib 2.0.21); a new one fails the build.
 *  3. No class may reference the methods that contain it (found with `javap -c`, see DECISIONS_LOG D20).
 */
val reviewedIndyClasses = setOf("kotlin/comparisons/ComparisonsKt__ComparisonsKt", "kotlin/streams/jdk8/StreamsKt", "kotlin/uuid/Uuid")
fun isForbiddenRef(owner: String, name: String, desc: String): Boolean = when (owner) {
  "kotlin/comparisons/ComparisonsKt", "kotlin/comparisons/ComparisonsKt__ComparisonsKt" ->
    name in setOf("nullsFirst", "nullsLast", "then", "thenDescending") ||
      (name == "compareBy" && desc == "([Lkotlin/jvm/functions/Function1;)Ljava/util/Comparator;")
  else -> unsafePackages.any { owner.startsWith(it) }   // indy in <clinit>: merely loading these classes is unsafe
}
// Whole packages that must never be touched from outside (kotlin.uuid.Uuid has invokedynamic in its static initializer).
val unsafePackages = listOf("kotlin/uuid/", "kotlin/streams/jdk8/")

class ClassInfo(val name: String, val hasIndy: Boolean, val refs: List<Triple<String, String, String>>)

fun parseClass(d: ByteArray): ClassInfo {
  fun u2(i: Int) = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
  val n = u2(8)
  val tag = IntArray(n); val a = IntArray(n); val b = IntArray(n); val utf = arrayOfNulls<String>(n)
  var i = 10; var k = 1; var indy = false
  while (k < n) {
    val t = d[i].toInt() and 0xff; tag[k] = t
    when (t) {
      1 -> { val l = u2(i + 1); utf[k] = String(d, i + 3, l, Charsets.UTF_8); i += 3 + l }
      7, 8, 16, 19, 20 -> { a[k] = u2(i + 1); i += 3 }
      9, 10, 11, 12 -> { a[k] = u2(i + 1); b[k] = u2(i + 3); i += 5 }
      3, 4 -> i += 5
      17, 18 -> { if (t == 18) indy = true; i += 5 }
      5, 6 -> { i += 9; k++ }
      15 -> i += 4
      else -> throw GradleException("unknown constant pool tag $t")
    }
    k++
  }
  val thisClass = utf[a[u2(i + 2)]] ?: "?"
  val refs = (1 until n).filter { tag[it] == 10 || tag[it] == 11 }.map { r ->
    val owner = utf[a[a[r]]] ?: ""; val nat = b[r]
    Triple(owner, utf[a[nat]] ?: "", utf[b[nat]] ?: "")
  }
  return ClassInfo(thisClass, indy, refs)
}

fun checkNoInvokeDynamicReachable(appRoots: List<File>, libRoot: File) {
  val problems = ArrayList<String>()
  fun scan(root: File, isApp: Boolean) = root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
    val ci = parseClass(f.readBytes())
    if (ci.hasIndy && (isApp || ci.name !in reviewedIndyClasses)) problems += "invokedynamic in ${ci.name}"
    val insideUnsafePkg = unsafePackages.any { ci.name.startsWith(it) }   // package-internal references are irrelevant
    if (ci.name !in reviewedIndyClasses && !insideUnsafePkg && !ci.name.startsWith("kotlin/comparisons/ComparisonsKt"))
      for ((o, nm, ds) in ci.refs) if (isForbiddenRef(o, nm, ds)) problems += "${ci.name} calls $o.$nm$ds"
  }
  appRoots.filter { it.exists() }.forEach { scan(it, true) }
  scan(libRoot, false)
  if (problems.isNotEmpty()) throw GradleException("Android-unsafe invokedynamic reachable:\n  " + problems.take(20).joinToString("\n  "))
  println("invokedynamic check: OK")
}
