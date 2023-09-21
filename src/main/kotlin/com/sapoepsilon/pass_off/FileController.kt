package com.sapoepsilon.pass_off

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.io.*
import java.util.zip.ZipInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder
import org.springframework.web.bind.annotation.CrossOrigin
import java.util.zip.ZipEntry
import java.util.zip.ZipException

@CrossOrigin(origins = ["http://localhost:3000"])
@RestController
class FileController {

    companion object {
        private val logger = LoggerFactory.getLogger(FileController::class.java)
    }

    private var process: Process? = null
    private var outputReader: BufferedReader? = null
    private var inputWriter: BufferedWriter? = null
    private var classFileAbsoluteDirectory: File? = null

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<String> {
        logger.info("File upload initiated.")

        if (file.size > 1024 * 1024) {
            logger.warn("File size exceeds limit.")
            return ResponseEntity.badRequest().body("File size should be less than 1MB")
        }

        return try {
            val tempDir = Files.createTempDirectory("javaCode")
            val zipFilePath = Paths.get(tempDir.toString(), "code.zip")
            Files.write(zipFilePath, file.bytes)

            // Unzip the file using Unix's built-in unzip command
            val unzipDir = Paths.get(tempDir.toString(), "unzipDir")
            Files.createDirectories(unzipDir)
            val unzipProcess = ProcessBuilder("unzip", "-d", unzipDir.toString(), zipFilePath.toString())
                .start()
            unzipProcess.waitFor()

            if (unzipProcess.exitValue() != 0) {
                logger.info("Unzip failed: ${unzipProcess.inputStream.bufferedReader().readText()}")
                return ResponseEntity.status(500).body("Unzip failed: ${unzipProcess.inputStream.bufferedReader().readText()}")
            }

            val charset: CharsetDecoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)

            val mainClassPath = findMainClass(unzipDir, charset) ?: return ResponseEntity.status(500).body("Main class not found")
            logger.info("Main class found at: $mainClassPath")

            val compileProcess = ProcessBuilder("javac", mainClassPath)
                .directory(unzipDir.toFile())
                .redirectErrorStream(true)
                .start()
            compileProcess.waitFor()

            if (compileProcess.exitValue() != 0) {
                logger.info("Compilation failed: ${compileProcess.inputStream.bufferedReader().readText()}")
                return ResponseEntity.status(500).body("Compilation failed: ${compileProcess.inputStream.bufferedReader().readText()}")
            }

            val classFileRelativeDirectory = Paths.get(mainClassPath).parent
            classFileAbsoluteDirectory = unzipDir.resolve(classFileRelativeDirectory).toFile()

            ResponseEntity.ok("File uploaded successfully. Run the /run endpoint to execute the program.")
        } catch (e: Exception) {
            logger.error("An error occurred: ${e.message}")
            ResponseEntity.status(500).body("Failed to upload file: ${e.message}")
        }
    }
    @PostMapping("/run")
    fun run(): ResponseEntity<String> {
        logger.info("Java program starting.")

        if (classFileAbsoluteDirectory == null) {
            return ResponseEntity.status(500).body("No uploaded file found to run.")
        }

        process = ProcessBuilder("java", "Main")
            .directory(classFileAbsoluteDirectory)
            .redirectErrorStream(true)
            .start()

        outputReader = BufferedReader(InputStreamReader(process!!.inputStream))
        inputWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

        val javaOutput = StringBuilder()
        var line: String?
        while (outputReader?.readLine().also { line = it } != null) {
            javaOutput.append(line).append("\n")
        }

        if (process!!.waitFor() != 0) {
            logger.info("Java program execution failed: $javaOutput")
            return ResponseEntity.status(500).body("Java program execution failed: $javaOutput")
        }

        return ResponseEntity.ok("Java program started. Output: $javaOutput")
    }

    @PostMapping("/interact")
    fun interactWithConsole(@RequestBody input: String): ResponseEntity<String> {
        logger.info("Interacting with Java code.")

        inputWriter?.write("$input\n")
        inputWriter?.flush()

        val output = StringBuilder()
        var line: String?
        while (outputReader?.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }

        return ResponseEntity.ok("Output: $output")
    }

    private class MainClassFoundException(val mainClassPath: String) : RuntimeException()

    private fun findMainClass(root: Path, charset: CharsetDecoder): String? {
        var mainClassPath: String? = null
        try {
            Files.walkFileTree(root, setOf(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val filePath = file.toString()
                    if (filePath.endsWith(".java") && !filePath.contains("__MACOSX") && !filePath.startsWith(".")) {
                        try {
                            Files.newBufferedReader(file, Charset.forName("UTF-8")).useLines { lines ->
                                lines.any {
                                    if (it.contains("public static void main")) {
                                        mainClassPath = file.toString().substringAfter(root.toString() + File.separator)
                                        throw MainClassFoundException(mainClassPath!!)
                                    }
                                    false
                                }
                            }
                        } catch (e: java.nio.charset.MalformedInputException) {
                            logger.error("Error reading file: $file, skipping.")
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: MainClassFoundException) {
            mainClassPath = e.mainClassPath
        }
        return mainClassPath
    }
}
