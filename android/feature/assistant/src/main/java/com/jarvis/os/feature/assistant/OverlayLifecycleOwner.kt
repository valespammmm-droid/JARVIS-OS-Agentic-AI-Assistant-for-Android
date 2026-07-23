package com.jarvis.os.feature.assistant

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Custom LifecycleOwner for ComposeView inside a WindowManager overlay.
 *
 * A Service is NOT a LifecycleOwner. ComposeView requires ViewTreeLifecycleOwner,
 * ViewTreeSavedStateRegistryOwner, and ViewTreeViewModelStoreOwner to be set on
 * the view tree. Without these, Compose silently fails to render or recompose.
 *
 * This class provides all three owners so that Compose works correctly
 * inside a foreground service overlay.
 */
class OverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    /**
     * Call in Service.onCreate() — sets lifecycle to CREATED → STARTED → RESUMED.
     */
    fun onCreate() {
        performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /**
     * Call in Service.onDestroy() — sets lifecycle to DESTROYED and clears ViewModelStore.
     */
    fun onDestroy() {
        handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
