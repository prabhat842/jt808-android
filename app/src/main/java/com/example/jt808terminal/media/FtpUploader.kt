package com.example.jt808terminal.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * Minimal FTP STOR client for 0x9206 file upload — JT/T 1078-2016 §5.6.5.
 *
 * Implements passive-mode FTP:
 *   USER → PASS → TYPE I → PASV → STOR → data transfer → file close
 *
 * On success sends the completion callback so TerminalService can send 0x1206.
 * Upload can be cancelled by closing [isCancelled].
 */
class FtpUploader(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val remoteDir: String,
) {
    @Volatile var isCancelled = false
    @Volatile var isPaused    = false

    /**
     * Uploads all [files] to the FTP server under [remoteDir].
     * Returns true if all files uploaded successfully.
     */
    suspend fun upload(files: List<File>): Boolean = withContext(Dispatchers.IO) {
        var ctrl: Socket? = null
        try {
            ctrl = Socket(host, port)
            val reader = BufferedReader(InputStreamReader(ctrl.getInputStream()))
            val writer = PrintWriter(ctrl.getOutputStream(), true)

            fun read(): String {
                var line: String
                do { line = reader.readLine() ?: throw Exception("FTP connection closed") }
                while (line.length >= 4 && line[3] == '-')   // multiline responses
                return line
            }

            fun send(cmd: String): String { writer.println(cmd); return read() }
            fun expect(reply: String, cmd: String): String {
                val r = send(cmd)
                if (!r.startsWith(reply)) throw Exception("FTP: expected $reply got $r (cmd=$cmd)")
                return r
            }

            read()                                     // 220 welcome
            expect("331", "USER $username")
            expect("230", "PASS $password")
            expect("200", "TYPE I")                    // binary mode

            for (file in files) {
                if (isCancelled) return@withContext false
                while (isPaused) {
                    Thread.sleep(500)
                    if (isCancelled) return@withContext false
                }
                uploadFile(file, reader, writer, ::read, ::expect)
                Log.i(TAG, "Uploaded ${file.name}")
            }

            send("QUIT")
            true
        } catch (e: Exception) {
            Log.e(TAG, "FTP upload failed: ${e.message}")
            false
        } finally {
            ctrl?.close()
        }
    }

    private fun uploadFile(
        file: File,
        reader: BufferedReader,
        writer: PrintWriter,
        read: () -> String,
        expect: (String, String) -> String,
    ) {
        // Passive mode: server picks data port
        val pasvReply = expect("227", "PASV")
        val (dataHost, dataPort) = parsePasv(pasvReply)

        val remotePath = "$remoteDir/${file.name}".replace("//", "/")
        writer.println("STOR $remotePath")

        Socket(dataHost, dataPort).use { data ->
            val out = data.getOutputStream()
            file.inputStream().use { input ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    if (isCancelled) return
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }

        val r = read()   // 226 Transfer complete
        if (!r.startsWith("1") && !r.startsWith("2")) {
            throw Exception("FTP STOR failed: $r")
        }
    }

    /**
     * Parses PASV response: "227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)"
     * Returns host string and port number.
     */
    private fun parsePasv(reply: String): Pair<String, Int> {
        val m = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(reply)
            ?: throw Exception("Cannot parse PASV response: $reply")
        val (h1, h2, h3, h4, p1, p2) = m.destructured
        val host = "$h1.$h2.$h3.$h4"
        val port = p1.toInt() * 256 + p2.toInt()
        return host to port
    }

    companion object {
        private const val TAG = "FtpUploader"
    }
}
