package kscript.app

import org.docopt.DocOptWrapper
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import kotlin.system.exitProcess


/**
 * A kscript - Scripting enhancements for Kotlin
 *
 * For details and license see https://github.com/holgerbrandl/kscript
 *
 * @author Holger Brandl
 */

const val KSCRIPT_VERSION = "2.6.0"

val selfName = System.getenv("CUSTOM_KSCRIPT_NAME") ?: "kscript"

val USAGE = """
$selfName - Enhanced scripting support for Kotlin on *nix-based systems.

Usage:
 $selfName [options] <script> [<script_args>]...
 $selfName --clear-cache
 $selfName --self-update

The <script> can be a  script file (*kts), a script URL, - for stdin, a *.kt source file with a main method, or some kotlin code.

Use '--clear-cache' to wipe cached script jars and urls
Use '--self-update' to update kscript to the latest version

Options:
 -i --interactive        Create interactive shell with dependencies as declared in script
 -t --text               Enable stdin support API for more streamlined text processing
 --idea                  Open script in temporary Intellij session
 -s --silent             Suppress status logging to stderr
 --package               Package script and dependencies into self-dependent binary
 -J<arg>                 -J is stripped and <arg> passed to Java as-is (if no KOTLIN_OPTS is given)
 -Dname=value            Set a system JVM property (if no KOTLIN_OPTS is given)

Copyright : 2017 Holger Brandl
License   : MIT
Version   : v$KSCRIPT_VERSION
Website   : https://github.com/holgerbrandl/kscript
""".trim()

// see https://stackoverflow.com/questions/585534/what-is-the-best-way-to-find-the-users-home-directory-in-java
// See #146 "allow kscript cache dir to be configurable" for details
val KSCRIPT_CACHE_DIR = System.getenv("KSCRIPT_CACHE_DIR")?.let { File(it) }
    ?: File(System.getProperty("user.home")!!, ".kscript")

// use lazy here prevent empty dirs for regular scripts https://github.com/holgerbrandl/kscript/issues/130
val SCRIPT_TEMP_DIR by lazy { createTempDir() }


fun main(args: Array<String>) {
    // skip org.docopt for version and help to allow for lazy version-check
    if (args.size == 1 && listOf("--help", "-h", "--version", "-v").contains(args[0])) {
        info(USAGE)
        versionCheck()
        quit(0)
    }

    // note: with current impt we still don't support `kscript -1` where "-1" is a valid kotlin expression
    val userArgs = args.dropWhile { it.startsWith("-") && it != "-" }.drop(1)
    val kscriptArgs = args.take(args.size - userArgs.size)

    val docopt = DocOptWrapper(kscriptArgs, USAGE)
    val loggingEnabled = !docopt.getBoolean("silent")


    // create cache dir if it does not yet exist
    if (!KSCRIPT_CACHE_DIR.isDirectory) {
        KSCRIPT_CACHE_DIR.mkdir()
    }

    // optionally clear up the jar cache
    if (docopt.getBoolean("clear-cache")) {
        info("Cleaning up cache...")
        KSCRIPT_CACHE_DIR.listFiles().forEach { it.delete() }
        //        evalBash("rm -f ${KSCRIPT_CACHE_DIR}/*")
        quit(0)
    }

    // optionally self-update kscript ot the newest version
    // (if not local copy is not being maintained by sdkman)
    if (docopt.getBoolean(("self-update"))) {
        selfUpdate()
        quit(0)
    }

    // infer KOTLIN_HOME if not set
    val KOTLIN_HOME = System.getenv("KOTLIN_HOME") ?: guessKotlinHome() ?: run {
        errorMsg("KOTLIN_HOME is not set and could not be inferred from context")
        quit(1)
    }

    val kotlinRunner = KotlinRunner(KOTLIN_HOME)
    val enableSupportApi = docopt.getBoolean("text")

    // Resolve the script resource argument into an actual file
    val scriptResource = docopt.getString("script")

    prepareJarAndRun(KOTLIN_HOME, kotlinRunner, scriptResource, docopt,
            userArgs = userArgs, loggingEnabled = loggingEnabled, enableSupportApi = enableSupportApi)
}


fun prepareJarAndRun(KOTLIN_HOME: String,
                     kotlinRunner: KotlinRunner,
                     scriptResource: String,
                     docopt: DocOptWrapper? = null,
                     userArgs: List<String> = listOf(),
                     loggingEnabled: Boolean = true,
                     enableSupportApi: Boolean = false,
                     prevClasspath: String? = null): String {
    val (rawScriptFile, includeContext) = prepareScript(scriptResource)

    // post process script (text-processing mode, custom dsl preamble, resolve includes)
    // and finally resolve all includes (see https://github.com/holgerbrandl/kscript/issues/34)
    val (scriptFile, includeURLs) = resolveIncludes(resolvePreambles(rawScriptFile, enableSupportApi), includeContext)

    val script = Script(scriptFile)

    //  Create temporary dev environment
    docopt?.let {
        if (docopt.getBoolean("idea")) {
            // TODO Compile annotation
            evalBash(launchIdeaWithKscriptlet(rawScriptFile, script.dependencies, script.repos, includeURLs))
            exitProcess(0)
        }
    }

    val dependsClasspath = resolveDependencies(script.dependencies, script.repos, loggingEnabled)

    // Extract kotlin arguments
    val kotlinOpts = argsToList(script.runtimeOptions)
    val compilerOpts = argsToList(script.compilerOptions)

    val scriptFileExt = scriptFile.extension
    val scriptCheckSum = md5(scriptFile)


    // Even if we just need and support the //ENTRY directive in case of kt-class
    // files, we check it here to fail if it was used in kts files.
    errorIf(script.entryPoint != null && scriptFileExt == "kts") {
        "@Entry directive is just supported for kt class files"
    }

    val jarFile = if (scriptFile.nameWithoutExtension.endsWith(scriptCheckSum)) {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + ".jar")
    } else {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + "." + scriptCheckSum + ".jar")
    }

    // Capitalize first letter and get rid of dashes (since this is what kotlin compiler is doing for the wrapper to create a valid java class name)
    // For valid characters see https://stackoverflow.com/questions/4814040/allowed-characters-in-filename
    val className = scriptFile.nameWithoutExtension
            .replace("[^A-Za-z0-9]".toRegex(), "_")
            .capitalize()
             // also make sure that it is a valid identifier by avoiding an initial digit (to stay in sync with what the kotlin script compiler will do as well)
            .let { if ("^[0-9]".toRegex().containsMatchIn(it)) "_" + it else it }
    


    // Define the entrypoint for the scriptlet jar
    val execClassName = if (scriptFileExt == "kts") {
        "Main_${className}"
    } else {
        // extract package from kt-file
        """${script.pckg ?: ""}${script.entryPoint ?: "${className}Kt"}"""
    }

    val jars = script.jars.map {
        val url = resolveFile(it, includeContext)
        url.file
    }.joinToString(":")

    var currentClasspath: String? = listOfNotNull(jars, dependsClasspath, prevClasspath).joinToString(CP_SEPARATOR_CHAR)
    if (currentClasspath.isNullOrBlank()) currentClasspath = null

    script.compiles.forEach {
        val url = resolveFile(it, includeContext)
        val file = url.file

        // only top call maintains docopt
        currentClasspath = prepareJarAndRun(KOTLIN_HOME, kotlinRunner, file, docopt = null,
                loggingEnabled = loggingEnabled, enableSupportApi = enableSupportApi, prevClasspath = currentClasspath)
    }

    val classpath = currentClasspath

    // If scriplet jar ist not cached yet, build it
    if (!jarFile.isFile) {
        // create main-wrapper for kts scripts
        val wrapperFile =
                if (scriptFileExt == "kts") generateWrapperFile(execClassName, script, className)
                else null
        val sourceFiles = listOfNotNull(scriptFile, wrapperFile)
        kotlinRunner.compile(compilerOpts, jarFile, sourceFiles, classpath)
    }
    docopt?.let {
        //if requested try to package the into a standalone binary
        if (docopt.getBoolean("package")) {
            val binaryName = if (File(scriptResource).run { canRead() && listOf("kts", "kt").contains(extension) }) {
                File(scriptResource).nameWithoutExtension
            } else {
                "k" + scriptFile.nameWithoutExtension
            }

            // TODO Compile annotation
            packageKscript(jarFile, execClassName, script.dependencies, script.repos, kotlinOpts, binaryName)

            quit(0)
        }

        //  Optionally enter interactive mode
        if (docopt.getBoolean("interactive")) {
            System.err.println("Creating REPL from ${scriptFile}")
            kotlinRunner.interactiveShell(jarFile, classpath, compilerOpts, kotlinOpts)
            exitProcess(0)
        }

        // TODO .kt file may not need kotlin-script-runtime
        val scriptClassPath = listOf(jarFile, joinToPathString(KOTLIN_HOME, "lib", "kotlin-script-runtime.jar"), classpath).joinToString(CP_SEPARATOR_CHAR)

        exitProcess(kotlinRunner.runScript(scriptClassPath, execClassName, userArgs, kotlinOpts))
    }
    return listOfNotNull(classpath, jarFile.absolutePath).joinToString(CP_SEPARATOR_CHAR)
}

private fun generateWrapperFile(execClassName: String, script: Script, className: String): File {
    val mainKotlin = File(createTempDir("kscript"), execClassName + ".kt")

    val classReference = (script.pckg ?: "") + className

    mainKotlin.writeText("""
            class Main_${className}{
                companion object {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        val script = Main_${className}::class.java.classLoader.loadClass("${classReference}")
                        script.getDeclaredConstructor(Array<String>::class.java).newInstance(args);
                    }
                }
            }
            """.trimIndent())
    return mainKotlin
}

private fun selfUpdate() {
    if (true || evalBash("which kscript | grep .sdkman").stdout.isNotBlank()) {
        info("Installing latest version of kscript...")
        // create update script
        val updateScript = File(KSCRIPT_CACHE_DIR, "self_update.sh").apply {
            writeText("""
                #!/usr/bin/env bash
                export SDKMAN_DIR="${"$"}{HOME}/.sdkman"
                source "${"$"}{SDKMAN_DIR}/bin/sdkman-init.sh"
                sdkman_auto_answer=true && sdk install kscript
                """.trimIndent())
            setExecutable(true)
        }

        evalBash(updateScript.absolutePath)
    } else {
        info("Self-update is currently just supported via sdkman.")
        info("Please download a new release from https://github.com/holgerbrandl/kscript")
        // todo port sdkman-indpendent self-update
    }
}


/** Determine the latest version by checking github repo and print info if newer version is available. */
private fun versionCheck() {

    //    val latestVersion = fetchFromURL("https://git.io/v9R73")?.useLines {
    //    val kscriptRawReleaseURL= "https://git.io/v9R73"
    // todo use the actual kscript.app.Kscript.kt here to infer version
    val kscriptRawReleaseURL = "https://raw.githubusercontent.com/holgerbrandl/kscript/releases/kscript"

    val latestVersion = try {
        BufferedReader(InputStreamReader(URL(kscriptRawReleaseURL).openStream())).useLines {
            it.first { it.startsWith("KSCRIPT_VERSION") }.split("=")[1]
        }
    } catch (e: UnknownHostException) {
        return // skip version check here, since the use has no connection to the internet at the moment
    }

    fun padVersion(version: String) = java.lang.String.format("%03d%03d%03d", *version.split(".").map { Integer.valueOf(it) }.toTypedArray())

    if (padVersion(latestVersion) > padVersion(KSCRIPT_VERSION)) {
        info("""A new version (v${latestVersion}) of kscript is available. Use 'kscript --self-update' to update your local kscript installation""")
    }
}


fun prepareScript(scriptResource: String): Pair<File, URI> {
    var scriptFile: File?

    // we need to keep track of the scripts dir or the working dir in case of stdin script to correctly resolve includes
    var includeContext: URI = File(".").toURI()

    // map script argument to script file
    scriptFile = with(File(scriptResource)) {
        if (!canRead()) {
            // not a file so let's keep the script-file undefined here
            null
        } else if (listOf("kts", "kt").contains(extension)) {
            // update include context
            includeContext = this.absoluteFile.parentFile.toURI()

            // script input is a regular script or clas file
            this
        } else {
            // if we can "just" read from script resource create tmp file
            // i.e. script input is process substitution file handle
            // not FileInputStream(this).bufferedReader().use{ readText()} does not work nor does this.readText
            createTmpScript(FileInputStream(this).bufferedReader().readText())
        }
    }

    // support stdin
    if (scriptResource == "-" || scriptResource == "/dev/stdin") {
        val scriptText = generateSequence() { readLine() }.joinToString("\n").trim()
        scriptFile = createTmpScript(scriptText)
    }


    // Support URLs as script files
    if (scriptResource.startsWith("http://") || scriptResource.startsWith("https://")) {
        scriptFile = fetchFromURL(scriptResource)

        includeContext = URI(scriptResource.run { substring(lastIndexOf('/') + 1) })
    }


    // Support for support process substitution and direct script arguments
    if (scriptFile == null && !scriptResource.endsWith(".kts") && !scriptResource.endsWith(".kt")) {
        val scriptText = if (File(scriptResource).canRead()) {
            File(scriptResource).readText().trim()
        } else {
            // the last resort is to assume the input to be a kotlin program
            scriptResource.trim()
        }

        scriptFile = createTmpScript(scriptText)
    }

    // just proceed if the script file is a regular file at this point
    errorIf(scriptFile == null || !scriptFile.canRead()) {
        "Could not read script argument '$scriptResource'"
    }

    // note script file must be not null at this point

    return Pair(scriptFile!!, includeContext)
}


private fun resolvePreambles(rawScript: File, enableSupportApi: Boolean): File {
    // include preamble for custom interpreters (see https://github.com/holgerbrandl/kscript/issues/67)
    var scriptFile = rawScript

    System.getenv("CUSTOM_KSCRIPT_PREAMBLE")?.let { interpPreamble ->
        //        rawScript = Script(rawScript!!).prependWith(interpPreamble).createTmpScript()
        val preambleFile = File(SCRIPT_TEMP_DIR, "include_cache.${md5(interpPreamble)}.kt").apply {
            writeText(interpPreamble)
        }

        scriptFile = Script(scriptFile).prependWith("//INCLUDE ${preambleFile.absolutePath}").createTmpScript()
    }

    // prefix with text-processing preamble if kscript-support api is enabled
    if (enableSupportApi) {
        val textProcPreamble = """
            //DEPS com.github.holgerbrandl:kscript-support:1.2.5

            import kscript.text.*
            val lines = resolveArgFile(args)

            """.trimIndent()

        //        scriptFile = Script(scriptFile!!).prependWith(textProcPreamble).createTmpScript()
        val preambleFile = File(SCRIPT_TEMP_DIR, "include_cache.${md5(textProcPreamble)}.kt").apply {
            writeText(textProcPreamble)
        }

        scriptFile = Script(scriptFile).prependWith("//INCLUDE ${preambleFile.absolutePath}").createTmpScript()
    }
    return scriptFile
}


private fun postProcessScript(inputFile: File?, includeContext: URI): IncludeResult =
        resolveIncludes(inputFile!!, includeContext)
