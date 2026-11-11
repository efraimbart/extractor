package com.ashishpipaliya.extractor.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ashishpipaliya.extractor.generated.InitConfig
import com.ashishpipaliya.extractor.generated.InitResult
import com.ashishpipaliya.extractor.generated.VersionInfo
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Service responsible for library initialization and version management
 */
class LibraryService(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var initialized = false

    fun initialize(config: InitConfig, callback: (Result<InitResult>) -> Unit) {
        // Run initialization on main thread as required by youtubedl-android
        mainHandler.post {
            scope.launch {
                try {
                    YoutubeDL.getInstance().init(context)

                    if (config.enableFFmpeg) {
                        FFmpeg.getInstance().init(context)
                    }

                    if (config.enableAria2c) {
                        Aria2c.getInstance().init(context)
                    }

                    initialized = true

                    callback(Result.success(InitResult(success = true, errorMessage = null)))
                } catch (e: Exception) {
                    initialized = false
                    callback(
                        Result.success(
                            InitResult(
                                success = false,
                                errorMessage = e.message ?: "Unknown initialization error"
                            )
                        )
                    )
                }
            }
        }
    }

    fun getVersion(callback: (Result<VersionInfo>) -> Unit) {
        scope.launch {
            try {
                val versions = withContext(Dispatchers.IO) {
                    VersionInfo(
                        youtubeDlVersion = probeYtDlpVersion(),
                        ffmpegVersion = probeFfmpegVersion(),
                        pythonVersion = probePythonVersion(),
                    )
                }
                callback(Result.success(versions))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }

    fun isInitialized(): Boolean = initialized

    /** Live `--version` from the binary that runs — never SharedPreferences. */
    private fun probeYtDlpVersion(): String {
        return try {
            val request = YoutubeDLRequest(emptyList())
            request.addOption("--version")
            val version = YoutubeDL.getInstance().execute(request).out.trim()
            if (version.isEmpty()) "yt-dlp (unknown)" else "yt-dlp $version"
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp --version failed", e)
            "yt-dlp unavailable"
        }
    }

    private fun probeFfmpegVersion(): String {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!bin.exists()) return "FFmpeg missing"
        // libffmpeg.so is a real CLI binary, but its .so deps are unzipped into
        // no_backup/.../packages/ffmpeg/usr/lib by FFmpeg.init — same LD_LIBRARY_PATH
        // YoutubeDL sets when it invokes ffmpeg via --ffmpeg-location.
        val ffmpegLibDir = File(packagesDir(), "ffmpeg/usr/lib")
        if (!ffmpegLibDir.exists()) return "FFmpeg libs missing"

        val output = runProcess(
            command = listOf(bin.absolutePath, "-version"),
            env = mapOf("LD_LIBRARY_PATH" to bundledLdLibraryPath()),
        ) ?: return "FFmpeg unavailable"
        // Prefer the banner line ("ffmpeg version …") over linker noise.
        val banner = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ffmpeg version", ignoreCase = true) }
            ?: output.lineSequence().firstOrNull {
                it.isNotBlank() && !it.contains("CANNOT LINK", ignoreCase = true)
            }?.trim()
        return banner ?: "FFmpeg unavailable"
    }

    private fun probePythonVersion(): String {
        val pythonBin = File(context.applicationInfo.nativeLibraryDir, "libpython.so")
        if (!pythonBin.exists()) return "Python missing"

        val pythonDir = File(packagesDir(), "python")
        val pythonHome = File(pythonDir, "usr").absolutePath

        val output = runProcess(
            command = listOf(pythonBin.absolutePath, "-c", "import sys; print(sys.version.split()[0])"),
            env = mapOf(
                "LD_LIBRARY_PATH" to bundledLdLibraryPath(),
                "PYTHONHOME" to pythonHome,
                "HOME" to pythonHome,
                "TMPDIR" to context.cacheDir.absolutePath,
                "SSL_CERT_FILE" to File(pythonDir, "usr/etc/tls/cert.pem").absolutePath,
            ),
        )
        val version = output?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        return if (version.isNullOrEmpty()) "Python unavailable" else "Python $version"
    }

    private fun packagesDir(): File =
        File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")

    /** Matches YoutubeDL's process env so bundled native CLIs can resolve .so deps. */
    private fun bundledLdLibraryPath(): String {
        val packages = packagesDir()
        return listOf(
            File(packages, "python/usr/lib").absolutePath,
            File(packages, "ffmpeg/usr/lib").absolutePath,
            File(packages, "aria2c/usr/lib").absolutePath,
        ).joinToString(":")
    }

    private fun runProcess(command: List<String>, env: Map<String, String>): String? {
        return try {
            val builder = ProcessBuilder(command)
            builder.redirectErrorStream(true)
            val processEnv = builder.environment()
            processEnv["PATH"] = System.getenv("PATH") + ":" + context.applicationInfo.nativeLibraryDir
            env.forEach { (k, v) -> processEnv[k] = v }
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0 && output.isBlank()) null else output
        } catch (e: Exception) {
            Log.w(TAG, "version probe failed: ${command.joinToString(" ")}", e)
            null
        }
    }

    companion object {
        private const val TAG = "ExtractorLibrary"
    }
}
