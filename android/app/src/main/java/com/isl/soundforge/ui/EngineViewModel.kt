package com.isl.soundforge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.isl.soundforge.ai.GeminiAudioAnalyzer
import com.isl.soundforge.audio.AudioFileManager
import com.isl.soundforge.audio.AudioProcessor
import com.isl.soundforge.firebase.AuthManager
import com.isl.soundforge.firebase.ProcessingQueueManager
import com.isl.soundforge.firebase.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// State types
// ─────────────────────────────────────────────────────────────────────────────

enum class Screen(val route: String) {
    AUTH("auth"),
    HOME("home"),
    PROCESS("process"),
    LIBRARY("library"),
    SETTINGS("settings")
}

data class AudioItem(
    val id: String,
    val name: String,
    val uri: Uri? = null,
    val remoteUrl: String? = null,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val isProcessed: Boolean = false,
    val waveformData: FloatArray = FloatArray(0)
)

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Uploading(val progress: Float) : ProcessingState()
    object Analyzing : ProcessingState()
    data class Processing(val progress: Float, val label: String) : ProcessingState()
    object Downloading : ProcessingState()
    data class Done(val outputItem: AudioItem) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}

data class AppState(
    val isAuthenticated: Boolean = false,
    val currentUser: FirebaseUser? = null,
    val selectedItem: AudioItem? = null,
    val processedItem: AudioItem? = null,
    val processingState: ProcessingState = ProcessingState.Idle,
    val libraryItems: List<AudioItem> = emptyList(),
    val isRecording: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val recordingAmplitudeDb: Float = -60f,
    val geminiAnalysis: GeminiAudioAnalyzer.AnalysisResult? = null,
    val processingOptions: AudioProcessor.ProcessingOptions = AudioProcessor.ProcessingOptions(),
    val useCloudProcessing: Boolean = true,
    val geminiApiKey: String = "",
    val errorMessage: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

class EngineViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val authManager = AuthManager(app)
    private val storageManager = StorageManager()
    private val queueManager = ProcessingQueueManager()
    private val fileManager = AudioFileManager(app)
    private val audioProcessor = AudioProcessor(app)
    private val gemini = GeminiAudioAnalyzer()

    init {
        _state.update { it.copy(
            isAuthenticated = authManager.isAuthenticated,
            currentUser = authManager.currentUser
        ) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────────────────────────────────

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            runCatching { authManager.signInWithEmail(email, password) }
                .onSuccess { user ->
                    _state.update { it.copy(isAuthenticated = true, currentUser = user, errorMessage = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = e.message) }
                }
        }
    }

    fun createAccount(email: String, password: String) {
        viewModelScope.launch {
            runCatching { authManager.createAccountWithEmail(email, password) }
                .onSuccess { user ->
                    _state.update { it.copy(isAuthenticated = true, currentUser = user, errorMessage = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = e.message) }
                }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            runCatching { authManager.signInWithGoogle(idToken) }
                .onSuccess { user ->
                    _state.update { it.copy(isAuthenticated = true, currentUser = user, errorMessage = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = e.message) }
                }
        }
    }

    fun signOut() {
        authManager.signOut()
        _state.update { AppState() }
    }

    fun googleSignInIntent() = authManager.googleSignInIntent()

    // ─────────────────────────────────────────────────────────────────────────
    // File selection / import
    // ─────────────────────────────────────────────────────────────────────────

    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            runCatching { fileManager.readMeta(uri) }
                .onSuccess { meta ->
                    val item = AudioItem(
                        id = System.currentTimeMillis().toString(),
                        name = meta.name,
                        uri = uri,
                        durationMs = meta.durationMs,
                        sizeBytes = meta.sizeBytes,
                        waveformData = meta.waveformData
                    )
                    _state.update { it.copy(
                        selectedItem = item,
                        processedItem = null,
                        processingState = ProcessingState.Idle,
                        geminiAnalysis = null
                    ) }
                }
                .onFailure { e ->
                    _state.update { it.copy(errorMessage = "Couldn't read file: ${e.message}") }
                }
        }
    }

    fun selectLibraryItem(item: AudioItem) {
        _state.update { it.copy(
            selectedItem = item,
            processedItem = null,
            processingState = ProcessingState.Idle
        ) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Processing
    // ─────────────────────────────────────────────────────────────────────────

    fun updateProcessingOptions(options: AudioProcessor.ProcessingOptions) {
        _state.update { it.copy(processingOptions = options) }
    }

    fun toggleCloudProcessing(useCloud: Boolean) {
        _state.update { it.copy(useCloudProcessing = useCloud) }
    }

    /** Main entry point: analyze with Gemini, then process. */
    fun enhance() {
        val item = _state.value.selectedItem ?: return
        viewModelScope.launch {
            analyzeWithGemini(item)
            if (_state.value.useCloudProcessing) {
                processCloud(item)
            } else {
                processOnDevice(item)
            }
        }
    }

    private suspend fun analyzeWithGemini(item: AudioItem) {
        _state.update { it.copy(processingState = ProcessingState.Analyzing) }
        // Build characteristics from what we already measured in readMeta
        val chars = GeminiAudioAnalyzer.AudioCharacteristics(
            durationMs = item.durationMs,
            peakDb = -3f,      // placeholder — full measurement done in AudioProcessor
            rmsDb = -18f,
            noiseFloorDb = -45f,
            dynamicRangeDb = 42f,
            fileName = item.name
        )
        val result = gemini.analyze(chars, runtimeKey = _state.value.geminiApiKey)
        if (result != null) {
            _state.update { s -> s.copy(
                geminiAnalysis = result,
                processingOptions = s.processingOptions.copy(
                    noiseReduction = result.noiseReduction,
                    noiseThreshold = result.noiseThreshold,
                    roomCorrection = result.roomCorrection,
                    normalization = result.normalization,
                    targetLUFS = result.targetLUFS
                )
            ) }
        }
    }

    private suspend fun processOnDevice(item: AudioItem) {
        val uri = item.uri ?: run {
            _state.update { it.copy(processingState = ProcessingState.Error("No local file available")) }
            return
        }
        runCatching {
            val result = audioProcessor.processFile(
                uri, _state.value.processingOptions
            ) { progress ->
                val label = when {
                    progress < 0.5f -> "Decoding…"
                    progress < 0.8f -> "Applying DSP…"
                    else            -> "Encoding…"
                }
                _state.update { it.copy(processingState = ProcessingState.Processing(progress, label)) }
            }
            val outputItem = AudioItem(
                id = "${item.id}_processed",
                name = "Enhanced_${item.name}",
                uri = Uri.fromFile(result.outputFile),
                durationMs = result.durationMs,
                isProcessed = true
            )
            _state.update { it.copy(
                processingState = ProcessingState.Done(outputItem),
                processedItem = outputItem,
                libraryItems = it.libraryItems + outputItem
            ) }
        }.onFailure { e ->
            _state.update { it.copy(processingState = ProcessingState.Error(e.message ?: "Processing failed")) }
        }
    }

    private suspend fun processCloud(item: AudioItem) {
        val context = getApplication<Application>()
        runCatching {
            // 1. Copy to app storage so we have a stable File reference
            _state.update { it.copy(processingState = ProcessingState.Uploading(0f)) }
            val localFile = if (item.uri != null) {
                fileManager.copyToAppStorage(item.uri)
            } else error("No file URI")

            // 2. Upload to Firebase Storage — backend accesses the file by storage path,
            //    not download URL, so we don't need the return value here.
            storageManager.uploadRaw(localFile) { progress ->
                _state.update { it.copy(processingState = ProcessingState.Uploading(progress)) }
            }

            // 3. Enqueue job
            val opts = _state.value.processingOptions
            val jobId = queueManager.enqueue(ProcessingQueueManager.Job(
                rawFileName = localFile.name,
                noiseReduction = opts.noiseReduction,
                roomCorrection = opts.roomCorrection,
                normalization = opts.normalization,
                targetLUFS = opts.targetLUFS,
                noiseThreshold = opts.noiseThreshold
            ))

            // 4. Wait for the backend to finish — first() suspends until a terminal state arrives
            //    then automatically cancels the Firestore listener.
            _state.update { it.copy(processingState = ProcessingState.Processing(0f, "Backend processing…")) }
            val finalJob = queueManager.watchJob(jobId)
                .first { it?.status == "done" || it?.status == "error" }

            when (finalJob?.status) {
                "done" -> {
                    _state.update { it.copy(processingState = ProcessingState.Downloading) }
                    val outFile = java.io.File(context.cacheDir, "processed/${finalJob.outputFileName}")
                    outFile.parentFile?.mkdirs()
                    storageManager.downloadProcessed(finalJob.outputFileName, outFile)
                    val outputItem = AudioItem(
                        id = jobId,
                        name = "Enhanced_${item.name}",
                        uri = Uri.fromFile(outFile),
                        durationMs = item.durationMs,
                        isProcessed = true
                    )
                    _state.update { it.copy(
                        processingState = ProcessingState.Done(outputItem),
                        processedItem = outputItem,
                        libraryItems = it.libraryItems + outputItem
                    ) }
                }
                "error" -> {
                    _state.update { it.copy(
                        processingState = ProcessingState.Error(
                            finalJob.errorMessage.ifEmpty { "Backend error" }
                        )
                    ) }
                }
            }
        }.onFailure { e ->
            _state.update { it.copy(processingState = ProcessingState.Error(e.message ?: "Upload failed")) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Library
    // ─────────────────────────────────────────────────────────────────────────

    fun loadLibrary() {
        viewModelScope.launch {
            runCatching { queueManager.listCompletedJobs() }
                .onSuccess { jobs ->
                    val items = jobs.map { job ->
                        AudioItem(
                            id = job.id,
                            name = "Enhanced_${job.rawFileName}",
                            createdAt = job.createdAt,
                            isProcessed = true
                        )
                    }
                    _state.update { it.copy(libraryItems = items) }
                }
        }
    }

    fun deleteLibraryItem(item: AudioItem) {
        viewModelScope.launch {
            runCatching {
                queueManager.deleteJob(item.id)
                storageManager.deleteFile(item.name)
            }
            _state.update { it.copy(libraryItems = it.libraryItems.filter { i -> i.id != item.id }) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings
    // ─────────────────────────────────────────────────────────────────────────

    fun updateGeminiKey(key: String) {
        _state.update { it.copy(geminiApiKey = key) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
