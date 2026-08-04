package com.goodwy.filemanager.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.MainActivity
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.ftp.DynamicFtpUserManager
import com.goodwy.filemanager.ftp.FtpNetworkUtils
import com.goodwy.filemanager.ftp.FtpPasswordManager
import com.goodwy.filemanager.ftp.FtpServerState
import com.goodwy.filemanager.ftp.FtpServerUiState
import com.goodwy.filemanager.ftp.VirtualFtpFileSystemFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory

class FtpServerService : Service() {
    @Volatile
    private var ftpServer: FtpServer? = null

    companion object {
        private const val ACTION_START = "com.goodwy.filemanager.action.START_FTP_SERVER"
        private const val ACTION_STOP = "com.goodwy.filemanager.action.STOP_FTP_SERVER"
        private const val NOTIFICATION_CHANNEL_ID = "ftp_server"
        private const val NOTIFICATION_ID = 5783

        fun start(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FtpServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundServer()
            ACTION_STOP -> stopServer()
            else -> stopSelf(startId)
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground FTP service running when the app is removed from Recents.
        // The server must stop only through the explicit Stop action in the UI/notification.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ftpServer?.stop()
        ftpServer = null
        super.onDestroy()
    }

    private fun startForegroundServer() {
        createNotificationChannel()

        val existingState = FtpServerState.current
        if (ftpServer != null && existingState.isRunning) {
            startForegroundCompat(buildNotification(getString(R.string.ftp_server_running), existingState.address))
            return
        }

        val localIpAddress = FtpNetworkUtils.getLocalIpAddress()
        val port = FtpNetworkUtils.findAvailablePort()
        val address = if (localIpAddress.isNotEmpty()) {
            "ftp://$localIpAddress:$port"
        } else {
            ""
        }

        val username = config.ftpUsername
        val password = FtpPasswordManager.regeneratePassword()

        FtpServerState.setState(
            FtpServerUiState(
                isStarting = true,
                address = address,
                username = username,
                password = password
            )
        )
        startForegroundCompat(buildNotification(getString(R.string.ftp_server_starting), address))

        Thread {
            try {
                if (localIpAddress.isEmpty()) {
                    throw IllegalStateException(getString(R.string.ftp_no_local_ip_error))
                }

                val server = createFtpServer(localIpAddress, port)
                server.start()
                ftpServer = server

                FtpServerState.setState(
                    FtpServerState.current.copy(
                        isRunning = true,
                        isStarting = false,
                        address = address,
                        username = config.ftpUsername,
                        password = FtpPasswordManager.currentPassword,
                        error = ""
                    )
                )
                updateNotification(getString(R.string.ftp_server_running), address)
            } catch (exception: Exception) {
                ftpServer = null
                FtpServerState.setState(
                    FtpServerState.current.copy(
                        isRunning = false,
                        isStarting = false,
                        error = exception.message ?: getString(R.string.unknown_error_occurred)
                    )
                )
                stopServer(updateServiceState = false)
            }
        }.start()
    }

    private fun createFtpServer(localIpAddress: String, port: Int): FtpServer {
        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory().apply {
            setServerAddress(localIpAddress)
            setPort(port)
            setIdleTimeout(0)
        }

        serverFactory.addListener("default", listenerFactory.createListener())
        serverFactory.userManager = DynamicFtpUserManager(
            context = applicationContext,
            homeDirectory = "/",
            onSuccessfulLogin = ::handleSuccessfulLogin
        )
        serverFactory.fileSystem = VirtualFtpFileSystemFactory(applicationContext)

        return serverFactory.createServer()
    }

    private fun handleSuccessfulLogin() {
        // Keep the password stable while the FTP server is running.
        // A new password is generated only when the server starts again.
    }

    private fun stopServer(updateServiceState: Boolean = true, stopSelfService: Boolean = true) {
        ftpServer?.stop()
        ftpServer = null

        if (updateServiceState) {
            FtpServerState.setState(FtpServerUiState())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (stopSelfService) {
            stopSelf()
        }
    }

    private fun buildNotification(title: String, address: String): Notification {
        val stopIntent = Intent(this, FtpServerService::class.java).setAction(ACTION_STOP)
        val pendingStopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openNetworkTabIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_NETWORK_TAB, true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenNetworkTabIntent = PendingIntent.getActivity(
            this,
            1,
            openNetworkTabIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = address.ifEmpty { getString(R.string.ftp_server_starting) }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_network_vector)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingOpenNetworkTabIntent)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_network_vector,
                    getString(R.string.stop_service),
                    pendingStopIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(title: String, address: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, address))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.ftp_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.ftp_notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
