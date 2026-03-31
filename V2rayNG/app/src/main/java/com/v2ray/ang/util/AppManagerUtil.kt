package com.v2ray.ang.util

import android.content.Context
import com.v2ray.ang.dto.AngConfig
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

object ServerSelector {

    data class ServerTestResult(
        val config: AngConfig,
        val latency: Long,
        val isAvailable: Boolean
    )

    /**
     * Тестирует все серверы и возвращает лучший по задержке
     */
    suspend fun selectBestServer(
        context: Context,
        configs: List<AngConfig>,
        testHost: String = "8.8.8.8",
        testPort: Int = 53,
        timeoutMs: Int = 5000
    ): AngConfig? = withContext(Dispatchers.IO) {
        
        if (configs.isEmpty()) return@withContext null

        val results = mutableListOf<ServerTestResult>()
        val semaphore = kotlinx.coroutines.sync.Semaphore(10) // Ограничение параллельных тестов

        val deferredResults = configs.map { config ->
            async {
                semaphore.withPermit {
                    testServerConnection(config, testHost, testPort, timeoutMs)
                }
            }
        }

        deferredResults.awaitAll().filter { it.isAvailable }.minByOrNull { it.latency }?.config
    }

    /**
     * Проверяет доступность конкретного сервера
     */
    private suspend fun testServerConnection(
        config: AngConfig,
        testHost: String,
        testPort: Int,
        timeoutMs: Int
    ): ServerTestResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        var isAvailable = false
        
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(testHost, testPort), timeoutMs)
            socket.close()
            isAvailable = true
        } catch (e: Exception) {
            isAvailable = false
        }

        val latency = if (isAvailable) System.currentTimeMillis() - startTime else Long.MAX_VALUE
        
        ServerTestResult(config, latency, isAvailable)
    }

    /**
     * Проверяет доступность через HTTP запрос (более точный тест)
     */
    suspend fun testHttpAvailability(
        proxyHost: String = "127.0.0.1",
        proxyPort: Int = 10808,
        testUrl: String = "https://www.google.com/generate_204",
        timeoutMs: Int = 10000
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS, 
                InetSocketAddress(proxyHost, proxyPort)
            )
            val url = java.net.URL(testUrl)
            val connection = url.openConnection(proxy) as java.net.HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode == 204 || responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
}
