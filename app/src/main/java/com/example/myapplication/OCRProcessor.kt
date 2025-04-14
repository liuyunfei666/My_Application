package com.example.myapplication

import android.graphics.BitmapFactory
import com.googlecode.tesseract.android.TessBaseAPI

internal object OCRProcessor {
    fun recognize(imagePath: String?): String {
        val tess = TessBaseAPI()
        try {
            val language = "chi_sim"
            val dataPath = "/data/data/com.example.myapplication/files/tesseract/"
            tess.init(dataPath, language)

            val bitmap = BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                tess.setImage(bitmap)
                return tess.utF8Text
            } else {
                return "图片读取失败"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return "识别失败: " + e.message
        } finally {
            tess.end()
        }
    }
}