package dev.temproot.vpntether

import android.app.Application
import kotlinx.coroutines.*

class TetherApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings
    lateinit var root: RootExecutor
    lateinit var bridge: BridgeClient
    lateinit var detector: NetworkDetector
    lateinit var engine: RoutingEngine

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        root = RootExecutor()
        bridge = BridgeClient()
        detector = NetworkDetector(root)
        engine = RoutingEngine(root, bridge, settings)
    }
}
