package net.wavenet.proto

import java.io.File
import java.util.concurrent.TimeUnit

/** Helpers to run the real C++ `wavenode` binary from JVM tests (interoperability evidence). */
object Interop {
    val bin: File? = (System.getenv("WAVENODE_BIN") ?: "../../build/linux-node/wavenode").let { File(it) }.takeIf { it.canExecute() }

    fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(listOf(bin!!.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    class Serve(dataDir: File, vararg extra: String) : AutoCloseable {
        val proc: Process = ProcessBuilder(listOf(bin!!.path, "--data", dataDir.path, "serve", "--port", "0", "--bind", "127.0.0.1",
                                                  "--no-multicast", "--disc-port", "0") + extra).redirectErrorStream(true).start()
        val port: Int
        val log = StringBuilder()
        init {
            val rd = proc.inputStream.bufferedReader()
            var p = -1
            val deadline = System.currentTimeMillis() + 10_000
            while (p < 0 && System.currentTimeMillis() < deadline) {
                val line = rd.readLine() ?: break
                log.appendLine(line)
                Regex("serving on port (\\d+)").find(line)?.let { p = it.groupValues[1].toInt() }
            }
            check(p > 0) { "wavenode did not start: $log" }
            port = p
            Thread { try { rd.forEachLine { synchronized(log) { log.appendLine(it) } } } catch (e: Exception) { } }.apply { isDaemon = true }.start()
        }
        override fun close() { proc.destroy(); proc.waitFor(5, TimeUnit.SECONDS) }
    }
}
