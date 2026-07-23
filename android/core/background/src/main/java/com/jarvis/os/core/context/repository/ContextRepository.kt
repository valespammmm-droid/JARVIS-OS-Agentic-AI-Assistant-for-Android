package com.jarvis.os.core.context.repository

import android.content.Context
import android.util.Log
import com.jarvis.os.core.context.manager.*
import com.jarvis.os.core.context.model.ContextState
import com.jarvis.os.core.context.model.MotionType
import com.jarvis.os.core.context.rule.ContextAction
import com.jarvis.os.core.context.rule.RuleEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface ContextRepository {
    val contextState: StateFlow<ContextState>
    val triggeredActions: SharedFlow<List<ContextAction>>
    fun startEngine()
    fun stopEngine()
}

/**
 * Production-grade Context Repository aggregating Location, Motion, and Device state
 * streams into a unified ContextState flow and executing RuleEngine evaluation.
 */
class ContextRepositoryImpl(
    private val context: Context,
    private val locationMgr: LocationContextManager = LocationContextManagerImpl(context),
    private val motionMgr: MotionContextManager = MotionContextManagerImpl(context),
    private val deviceStateMgr: DeviceStateContextManager = DeviceStateContextManagerImpl(context),
    private val ruleEngine: RuleEngine = RuleEngine()
) : ContextRepository {

    companion object {
        private const val TAG = "JarvisContextRepo"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _contextState = MutableStateFlow(ContextState())
    override val contextState: StateFlow<ContextState> = _contextState.asStateFlow()

    private val _triggeredActions = MutableSharedFlow<List<ContextAction>>(replay = 1)
    override val triggeredActions: SharedFlow<List<ContextAction>> = _triggeredActions.asSharedFlow()

    private var isRunning = false

    override fun startEngine() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Initializing Context Awareness Engine...")

        locationMgr.startMonitoring()
        motionMgr.startMonitoring()
        deviceStateMgr.startMonitoring()

        // Sync Motion state changes into Location Manager to adjust GPS battery interval adaptively
        scope.launch {
            motionMgr.motionState.collect { motion ->
                locationMgr.onMotionStateChanged(motion.type)
            }
        }

        // Combine all streams into unified ContextState flow
        scope.launch {
            combine(
                locationMgr.locationState,
                motionMgr.motionState,
                deviceStateMgr.batteryState,
                deviceStateMgr.audioState,
                deviceStateMgr.networkState,
                deviceStateMgr.volumeState
            ) { args: Array<Any> ->
                val loc = args[0] as com.jarvis.os.core.context.model.LocationSnapshot
                val motion = args[1] as com.jarvis.os.core.context.model.MotionSnapshot
                val battery = args[2] as com.jarvis.os.core.context.model.BatterySnapshot
                val audio = args[3] as com.jarvis.os.core.context.model.AudioSnapshot
                val network = args[4] as com.jarvis.os.core.context.model.NetworkType
                val volume = args[5] as com.jarvis.os.core.context.model.VolumeProfile
                ContextState(
                    location = loc,
                    motion = motion,
                    battery = battery,
                    audio = audio,
                    network = network,
                    volumeProfile = volume,
                    timestamp = System.currentTimeMillis()
                )
            }.collect { newState ->
                _contextState.value = newState
                Log.d(TAG, "Emitted new unified ContextState: $newState")

                // Evaluate rule engine
                val actions = ruleEngine.evaluate(newState)
                if (actions.isNotEmpty()) {
                    _triggeredActions.emit(actions)
                }
            }
        }
    }

    override fun stopEngine() {
        if (!isRunning) return
        locationMgr.stopMonitoring()
        motionMgr.stopMonitoring()
        deviceStateMgr.stopMonitoring()
        scope.cancel()
        isRunning = false
        Log.i(TAG, "Stopped Context Awareness Engine.")
    }
}
