package com.v2ray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.service.V2RayProxyOnlyService
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    // === НОВЫЕ ПОЛЯ ДЛЯ АВТОПЕРЕКЛЮЧЕНИЯ ===
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverTestingJob: Job? = null
    private var isSwitchingServer = AtomicBoolean(false)
    private val serverLatencyCache = ConcurrentHashMap<String, Long>()
    private var lastSwitchedTime: Long = 0
    private const val MIN_SWITCH_INTERVAL_MS = 10000L // Минимум 10 сек между переключениями
    private const val SERVER_TEST_TIMEOUT_MS = 8000
    private const val MAX_PARALLEL_TESTS = 5
    // === КОНЕЦ НОВЫХ ПОЛЕЙ ===

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        Log.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

        // === НОВАЯ ЛОГИКА: Автовыбор лучшего сервера при старте ===
        val finalGuid = if (guid == null && SettingsManager.isAutoSelectBestServer()) {
            Log.i(AppConfig.TAG, "StartCore-Manager: Auto-selecting best server")
            runBlocking(Dispatchers.IO) {
                selectBestServer()?.also { bestGuid ->
                    Log.i(AppConfig.TAG, "StartCore-Manager: Selected best server: $bestGuid")
                    MmkvManager.setSelectServer(bestGuid)
                }
            }
        } else {
            guid
        }
        // === КОНЕЦ НОВОЙ ЛОГИКИ ===

        if (finalGuid != null) {
            MmkvManager.setSelectServer(finalGuid)
        }

        startContextService(context)
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    // === НОВЫЕ МЕТОДЫ ДЛЯ АВТОПЕРЕКЛЮЧЕНИЯ ===

    /**
     * Запрашивает переключение на лучший сервер (вызывается из V2RayVpnService при сбое)
     */
    fun requestServerSwitch() {
        if (isSwitchingServer.get()) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Server switch already in progress")
            return
        }

        // Проверяем интервал между переключениями
        val timeSinceLastSwitch = SystemClock.elapsedRealtime() - lastSwitchedTime
        if (timeSinceLastSwitch < MIN_SWITCH_INTERVAL_MS) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Switch too frequent, delaying")
            managerScope.launch {
                delay(MIN_SWITCH_INTERVAL_MS - timeSinceLastSwitch)
                performServerSwitch()
            }
            return
        }

        managerScope.launch {
            performServerSwitch()
        }
    }

    /**
     * Выполняет переключение на лучший доступный сервер
     */
    private suspend fun performServerSwitch() {
        if (!isSwitchingServer.compareAndSet(false, true)) return

        try {
            Log.i(AppConfig.TAG, "StartCore-Manager: Performing server switch")
            
            val currentGuid = MmkvManager.getSelectServer()
            val bestServer = selectBestServer(excludeGuid = currentGuid)
            
            if (bestServer == null) {
                Log.e(AppConfig.TAG, "StartCore-Manager: No alternative server available")
                // Перезапускаем текущий как последняя попытка
                restartCurrentServer()
                return
            }

            Log.i(AppConfig.TAG, "StartCore-Manager: Switching to server: $bestServer")
            
            // Отправляем команду на переключение в V2RayVpnService
            val service = getService() ?: return
            val intent = Intent(service, V2RayVpnService::class.java)
            intent.action = "SWITCH_SERVER"
            intent.putExtra("server_guid", bestServer)
            
            ContextCompat.startForegroundService(service, intent)
            
            lastSwitchedTime = SystemClock.elapsedRealtime()
            
            // Уведомляем UI о переключении
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, bestServer)
            
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Server switch failed", e)
        } finally {
            isSwitchingServer.set(false)
        }
    }

    /**
     * Перезапускает текущий сервер (последняя попытка восстановления)
     */
    private suspend fun restartCurrentServer() {
        Log.i(AppConfig.TAG, "StartCore-Manager: Restarting current server")
        val service = getService() ?: return
        
        withContext(Dispatchers.Main) {
            stopCoreLoop()
            delay(2000)
            startContextService(service)
        }
    }

    /**
     * Выбирает лучший сервер по задержке (параллельное тестирование)
     * @param excludeGuid GUID сервера для исключения (текущий нерабочий)
     * @return GUID лучшего сервера или null
     */
    suspend fun selectBestServer(excludeGuid: String? = null): String? = withContext(Dispatchers.IO) {
        val allServers = MmkvManager.decodeServerList()
            .mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { it.guid != excludeGuid }
            .filter { it.configType != EConfigType.POLICYGROUP } // Пропускаем группы
            .filter { isValidServer(it) }

        if (allServers.isEmpty()) {
            Log.w(AppConfig.TAG, "StartCore-Manager: No servers available for testing")
            return@withContext null
        }

        Log.i(AppConfig.TAG, "StartCore-Manager: Testing ${allServers.size} servers")

        // Используем семафор для ограничения параллельных тестов
        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_TESTS)
        val results = ConcurrentHashMap<String, Long>()

        val testJobs = allServers.map { server ->
            async {
                semaphore.withPermit {
                    val latency = testServerLatency(server)
                    if (latency >= 0) {
                        results[server.guid] = latency
                        Log.d(AppConfig.TAG, "StartCore-Manager: Server ${server.remarks} latency: ${latency}ms")
                    } else {
                        Log.d(AppConfig.TAG, "StartCore-Manager: Server ${server.remarks} unavailable")
                    }
                }
            }
        }

        testJobs.awaitAll()

        // Выбираем сервер с минимальной задержкой
        val bestServer = results.minByOrNull { it.value }?.key
        
        // Кэшируем результаты для статистики
        serverLatencyCache.putAll(results)
        
        bestServer?.also {
            val latency = results[it]
            Log.i(AppConfig.TAG, "StartCore-Manager: Best server selected: $it (${latency}ms)")
        }

        bestServer
    }

    /**
     * Тестирует задержку конкретного сервера через TCP handshake
     */
    private suspend fun testServerLatency(server: ProfileItem): Long = withContext(Dispatchers.IO) {
        try {
            val host = server.server ?: return@withContext -1
            val port = server.serverPort?.toIntOrNull() ?: return@withContext -1
            
            // Проверяем TCP соединение к серверу
            val startTime = SystemClock.elapsedRealtime()
            
            val socket = java.net.Socket()
            socket.connect(InetSocketAddress(host, port), SERVER_TEST_TIMEOUT_MS)
            socket.close()
            
            val latency = SystemClock.elapsedRealtime() - startTime
            
            // Дополнительно проверяем через прокси если уже запущен
            if (coreController.isRunning && latency > 0) {
                val proxyLatency = testThroughProxy()
                if (proxyLatency < 0) return@withContext -1 // Прокси не работает с этим сервером
            }
            
            latency
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Тестирует соединение через активный прокси
     */
    private suspend fun testThroughProxy(): Long = withContext(Dispatchers.IO) {
        try {
            val proxyPort = SettingsManager.getSocksPort()
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
            
            val url = URL("https://www.google.com/generate_204")
            val connection = url.openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = SERVER_TEST_TIMEOUT_MS
            connection.readTimeout = SERVER_TEST_TIMEOUT_MS
            
            val startTime = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val latency = SystemClock.elapsedRealtime() - startTime
            connection.disconnect()
            
            if (code == 204 || code == 200) latency else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Проверяет валидность конфигурации сервера
     */
    private fun isValidServer(config: ProfileItem): Boolean {
        return when (config.configType) {
            EConfigType.CUSTOM -> true
            else -> {
                val server = config.server.orEmpty()
                Utils.isValidUrl(server) || Utils.isPureIpAddress(server)
            }
        }
    }

    /**
     * Очищает кэш задержек (можно вызывать при обновлении списка серверов)
     */
    fun clearLatencyCache() {
        serverLatencyCache.clear()
    }

    // === КОНЕЦ НОВЫХ МЕТОДОВ ===

    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            return
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            return
        }

        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Invalid server configuration")
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isVpnMode = SettingsManager.isVpnMode()
        val intent = if (isVpnMode) {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting VPN service")
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Log.i(AppConfig.TAG, "StartCore-Manager: Starting Proxy service")
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start service", e)
        }
    }

    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            return false
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            return false
        }

        Log.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to get V2Ray config")
            return false
        }

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            // === НОВОЕ: Добавляем слушатель для события переключения сервера ===
            mFilter.addAction("com.v2ray.ang.ACTION_CONNECTION_FAILED")
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
            return false
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start core loop", e)
            return false
        }

        if (coreController.isRunning == false) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Core failed to start")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
            Log.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to complete startup", e)
            return false
        }
        return true
    }

    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long {
            return 0
        }

        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                }

                AppConfig.MSG_STATE_START -> {
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
                
                // === НОВОЕ: Обработка события сбоя соединения ===
                "com.v2ray.ang.ACTION_CONNECTION_FAILED" -> {
                    Log.w(AppConfig.TAG, "StartCore-Manager: Received connection failure broadcast")
                    requestServerSwitch()
                }
            }
        }
    }
}
