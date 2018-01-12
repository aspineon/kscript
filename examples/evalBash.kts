import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

val isWindows = System.getProperty("os.name").contains("Windows")

fun String.createBaseProcessBuilder(workingDir: File?): ProcessBuilder {
    val preamble = if (isWindows) arrayOf("cmd", "/c", "bash", "-c") else arrayOf("bash", "-c")
    val pb = ProcessBuilder(*preamble, if (isWindows) replace("\"", "\\\"") else this)
    workingDir?.let { pb.directory(workingDir) } 
    return pb
}

fun String.runBash(workingDir: File? = null) {
    val pb = createBaseProcessBuilder(workingDir)
    pb.inheritIO().start().waitFor()
}

try {
    println("runBash")
    "ls -l kscript".runBash()
    "kscript println\\(1\\)".runBash()
    "printf 'single quote'".runBash()
    "printf \"double quote\"".runBash()
    "printf \"back n \\n after newline\"".runBash()
    "kscript 'println(\"a\\nb c d\\nlast line \")'".runBash()
} catch (e: Exception) {
    e.printStackTrace()
}

fun String.startBashPipe(workingDir: File? = null): Process? {
    return try {
        val pb = createBaseProcessBuilder(workingDir)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE).start()
    } catch(e: IOException) {
        e.printStackTrace()
        null
    }
}

fun String.evalBash(workingDir: File? = null) : String? {
    val proc = startBashPipe(workingDir)
    proc?.waitFor()
    return proc?.inputStream?.bufferedReader()?.readText()
}

try {
    println("evalBash")
    println("ls -l kscript".evalBash())
    println("kscript println\\(1\\)".evalBash())
    println("printf 'single quote'".evalBash())
    println("printf \"double quote\"".evalBash())
    println("printf \"back n \\n after newline\"".evalBash())
    println("kscript 'println(\"a\\nb c d\\nlast line \")'".evalBash())
} catch (e: Exception) {
    e.printStackTrace()
}

