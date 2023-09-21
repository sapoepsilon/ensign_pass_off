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
                    val outputFile = Paths.get(tempDir.toString(), entry.name)
                    if (entry.isDirectory) {
                        Files.createDirectories(outputFile)
                    } else {
                        Files.createDirectories(outputFile.parent)
                        Files.newOutputStream(outputFile).use { fos ->
                            zipInputStream.copyTo(fos)
                        }
                    }
                }
            }

            val charset: CharsetDecoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)  // Ignore malformed input

            val mainClassPath = findMainClass(tempDir, charset) ?: return ResponseEntity.status(500).body("Main class not found")
            logger.info("Main class found at: $mainClassPath")

//            val mainClassName = mainClassPath
//                .replace(".java", ".class")  // Replace .java extension with .class
//                .replace("/", ".")  // Replace slashes with dots for class name
//                .substringAfter("src/")

            val compileProcess = ProcessBuilder("javac", mainClassPath)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start()
            compileProcess.waitFor()

            if (compileProcess.exitValue() != 0) {
                logger.info("Compilation failed: ${compileProcess.inputStream.bufferedReader().readText()}")
                return ResponseEntity.status(500).body("Compilation failed: ${compileProcess.inputStream.bufferedReader().readText()}")
            }

//            val parentDir = tempDir.resolve("java_test").toFile()
            val classFileRelativeDirectory = Paths.get(mainClassPath).parent
            val classFileAbsoluteDirectory = tempDir.resolve(classFileRelativeDirectory).toFile()
            logger.info("Main class in classFileAbosoluteDirecotry: $classFileAbsoluteDirectory")

            process = ProcessBuilder("java", "Main")
                .directory(classFileAbsoluteDirectory)
                .redirectErrorStream(true)
                .start()

            logger.info("Java program started.")

            outputReader = BufferedReader(InputStreamReader(process!!.inputStream))
            inputWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

            val javaOutput = StringBuilder()
            var line: String?
            while (outputReader?.readLine().also { line = it } != null) {
                javaOutput.append(line).append("\n")
            }

            if (process!!.waitFor() != 0) {
                logger.info("Java program execution failed: $javaOutput file path: ${tempDir.toString()}")
                return ResponseEntity.status(500).body("Java program execution failed: $javaOutput")
            }

            ResponseEntity.ok("File uploaded and Java program started. Output: $javaOutput")

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
