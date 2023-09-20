package com.sapoepsilon.pass_off

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.ZipInputStream

@RestController
class FileController {

    companion object {
        private val logger = LoggerFactory.getLogger(FileController::class.java)
    }

    private var process: Process? = null
    private var outputReader: BufferedReader? = null
    private var inputWriter: BufferedWriter? = null

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<String> {
        logger.info("File upload initiated.")

        if (file.size > 1024 * 1024) {
            logger.warn("File size exceeds limit.")
            return ResponseEntity.badRequest().body("File size should be less than 1MB")
        }

        return try {
            val tempDir = Files.createTempDirectory("javaCode")
            val zipPath = Paths.get(tempDir.toString(), "code.zip")
            Files.write(zipPath, file.bytes)

            ZipInputStream(Files.newInputStream(zipPath)).use { zipInputStream ->
                generateSequence { zipInputStream.nextEntry }.forEach { entry ->
                    if (entry.name.endsWith(".java")) {
                        val javaFile = Paths.get(tempDir.toString(), entry.name)
                        Files.newOutputStream(javaFile).use { fos ->
                            zipInputStream.copyTo(fos)
                        }
                    }
                }
            }

            // Compile Java code
            logger.info("Compiling Java code.")
            val compileProcess = ProcessBuilder("javac", "Main.java")
                .directory(tempDir.toFile())
                .start()
            compileProcess.waitFor()

            // Run Java code and capture its output
            logger.info("Running Java code.")
            process = ProcessBuilder("java", "Main")
                .directory(tempDir.toFile())
                .start()

            outputReader = BufferedReader(InputStreamReader(process!!.inputStream))
            inputWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            ResponseEntity.ok("File uploaded and Java program started.")
        } catch (e: Exception) {
            logger.error("An error occurred: ${e.message}")
            ResponseEntity.status(500).body("Failed to upload file: ${e.message}")
        }
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
}
