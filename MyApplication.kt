package com.example.myapplication

import android.app.Application

class MyApplication : Application() {
    // 这里可以添加全局初始化逻辑（如第三方库初始化）
    override fun onCreate() {
        super.onCreate()
    }
}
