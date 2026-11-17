package dev.temproot.vpntether

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch

class TetherService : Service() {
    companion object {
        const val ACTION_STOP = "dev.temproot.vpntether.STOP"
        const val EXTRA_TEXT = "text"
        private const val CH = "tether"

        fun start(ctx: Context, text: String) {
            ctx.startForegroundService(Intent(ctx, TetherService::class.java).putExtra(EXTRA_TEXT, text))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TetherService::class.java)) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val app = application as TetherApp
        if (i?.action == ACTION_STOP) {
            app.scope.launch { app.engine.disable() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Compartilhamento VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TetherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Compartilhando VPN")
            .setContentText(i?.getStringExtra(EXTRA_TEXT) ?: "Roteamento ativo")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Desativar", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        return START_STICKY
    }
}
