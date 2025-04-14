package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.example.myapplication.OCRProcessor.recognize
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class OCRBackgroundService : Service() {
    private val binder: IBinder = LocalBinder()
    private var tessBaseAPI: TessBaseAPI? = null
    private var serverPort = 12345
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognitionExecutor: ExecutorService = Executors.newCachedThreadPool()
    private var logFilePath: String? = null
    private var portFilePath: String? = null
    private var serverSocket: ServerSocket? = null
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "OCRServiceChannel"

    internal inner class LocalBinder : Binder() {
        val service: OCRBackgroundService
            get() = this@OCRBackgroundService
    }

    override fun onCreate() {
        super.onCreate()
        if (!hasRequiredPermissions()) {
            logMessage("缺少必要权限，服务无法启动")
            stopSelf()
            return
        }
        logFilePath = getExternalFilesDir(null)?.absolutePath + "/ocr_log.txt"
        portFilePath = getExternalFilesDir(null)?.absolutePath + "/ocr_port.txt"
        logMessage("服务 onCreate 方法开始执行")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        initTesseract()
        startTCPServer()
        logMessage("服务 onCreate 方法执行结束")
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.INTERNET
        )
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OCR Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun initTesseract() {
        logMessage("开始初始化 Tesseract")
        try {
            val language = "chi_sim"
            val dataPath = "${filesDir}/tesseract/"
            val tessdataPath = "$dataPath/tessdata/$language.traineddata"

            logMessage("训练数据路径: $tessdataPath")
            val tessdataDir = File(dataPath, "tessdata")
            if (!tessdataDir.exists()) tessdataDir.mkdirs()

            if (!File(tessdataPath).exists()) {
                logMessage("训练数据文件不存在，开始复制...")
                assets.open("tessdata/$language.traineddata").use { input ->
                    FileOutputStream(tessdataPath).use { output ->
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
                logMessage("训练数据文件复制完成")
            }

            tessBaseAPI = TessBaseAPI()
            tessBaseAPI?.init(dataPath, language)
            tessBaseAPI?.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD
            logMessage("Tesseract 初始化成功")
        } catch (e: Exception) {
            logException(e, "Tesseract 初始化发生异常")
        }
    }

    private fun startTCPServer() {
        executorService.submit {
            logMessage("开始启动 TCP 服务器，初始端口：$serverPort")
            val maxPort = 65535
            while (serverPort <= maxPort) {
                try {
                    serverSocket = ServerSocket(serverPort)
                    logMessage("TCP 服务器启动成功，监听端口：$serverPort")
                    writePortToFile(serverPort)
                    break
                } catch (e: IOException) {
                    logMessage("端口 $serverPort 被占用，尝试下一个端口")
                    serverPort++
                }
            }
            if (serverPort > maxPort) {
                logMessage("没有可用端口，TCP 服务器启动失败")
                stopSelf()
                return@submit
            }
            try {
                while (!Thread.currentThread().isInterrupted) {
                    serverSocket?.accept()?.let { socket ->
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                logException(e, "TCP 服务器运行异常")
                stopSelf()
            }
        }
    }

    private fun writePortToFile(port: Int) {
        portFilePath?.let { path ->
            logMessage("开始将端口号 $port 写入文件 $path")
            try {
                FileWriter(File(path)).use { writer ->
                    writer.write(port.toString())
                }
            } catch (e: IOException) {
                logException(e, "写入端口号到文件时发生异常")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        logMessage("新客户端连接：${socket.inetAddress.hostAddress}")
        try {
            BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                BufferedWriter(OutputStreamWriter(socket.getOutputStream())).use { writer ->
                    var imagePath: String?
                    while (reader.readLine().also { imagePath = it } != null) {
                        imagePath?.let { path ->
                            logMessage("接收到的图片路径: $path")
                            recognitionExecutor.submit {
                                try {
                                    val resultJson = recognizeImageFromPath(path)
                                    writer.write(resultJson)
                                    writer.newLine()
                                    writer.flush()
                                } catch (e: Exception) {
                                    logException(e, "处理客户端请求发生异常")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logException(e, "处理客户端请求发生异常")
        } finally {
            logMessage("客户端连接关闭")
        }
    }

    private fun recognizeImageFromPath(imagePath: String): String {
        logMessage("开始识别图片：$imagePath")
        val startTime = System.currentTimeMillis()
        try {
            val result = recognize(imagePath)
            return createResultJson(result, startTime).toString()
        } catch (e: Exception) {
            logException(e, "识别图片发生异常")
            return createResultJson("识别失败: ${e.message}", startTime).toString()
        }
    }

    @Throws(JSONException::class)
    private fun createResultJson(result: String, startTime: Long): JSONObject {
        val endTime = System.currentTimeMillis()
        return JSONObject()
            .put("result", result)
            .put("elapsed_time", endTime - startTime)
    }

    override fun onDestroy() {
        logMessage("服务 onDestroy 方法开始执行")
        try {
            tessBaseAPI?.end()
            serverSocket?.close()
            logMessage("ServerSocket 已关闭")
            executorService.shutdown()
            recognitionExecutor.shutdown()
        } catch (e: Exception) {
            logException(e, "onDestroy 发生异常")
        }
        logMessage("服务 onDestroy 方法执行结束")
        super.onDestroy()
    }

    private fun logMessage(message: String) {
        logFilePath?.let { path ->
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logEntry = "$timestamp - $message\n"
                val logFile = File(path)

                if (!logFile.exists()) {
                    logFile.createNewFile()
                }

                FileWriter(logFile, true).use { writer ->
                    writer.write(logEntry)
                }
                Log.d("OCRBackgroundService", "日志写入成功: $logEntry")
            } catch (e: IOException) {
                Log.e("OCRBackgroundService", "写入日志文件发生异常: ${e.message}", e)
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OCR 服务正在运行")
            .setContentText("OCR 服务已启动，正在监听请求")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_STICKY
    }

    private fun logException(e: Exception, message: String) {
        val log = """
            $message: ${e.message}
            ${e.stackTrace.contentToString()}
        """.trimIndent()
        Log.e("OCRBackgroundService", log)
        try {
            FileWriter(File(filesDir, "ocr_service_log.txt"), true).use { writer ->
                writer.write(log)
            }
        } catch (ioe: IOException) {
            Log.e("OCRBackgroundService", "写入异常日志失败: ${ioe.message}")
        }
    }
}
