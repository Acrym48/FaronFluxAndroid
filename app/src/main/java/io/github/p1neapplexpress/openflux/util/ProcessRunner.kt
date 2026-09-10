package io.github.p1neapplexpress.openflux.util

import java.util.concurrent.TimeUnit

object ProcessRunner {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun run(
        command: List<String>,
        workingDir: String? = null,
        timeoutMs: Long = 30_000L,
    ): Result {
        return try {
            val pb = ProcessBuilder(command).redirectErrorStream(false)
            if (workingDir != null) pb.directory(java.io.File(workingDir))
            val p = pb.start()

            val stdout = p.inputStream.bufferedReader().use { it.readText() }
            val stderr = p.errorStream.bufferedReader().use { it.readText() }

            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return Result(-1, stdout, "timeout after ${timeoutMs}ms: $stderr")
            }
            Result(p.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun killPidFile(path: String) {
        val f = java.io.File(path)
        if (!f.exists()) return
        try {
            val pid = f.readText().trim().toIntOrNull() ?: return
            ProcessBuilder("kill", pid.toString()).start().waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            f.delete()
        }
    }

    fun join(list: List<String>, sep: String): String =
        list.joinToString(sep)
}
