package com.visionassist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.location.Geocoder
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import androidx.core.app.ActivityCompat
import com.visionassist.data.AppDatabase
import com.visionassist.data.User
import com.visionassist.ml.ObjectDetectorHelper
import com.visionassist.utils.VoiceManager
import com.google.mlkit.vision.label.ImageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Stack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.Matrix

class MainActivity : AppCompatActivity(), OnMapReadyCallback, ObjectDetectorHelper.DetectorListener {

    private lateinit var voiceManager: VoiceManager
    private var detectorHelper: ObjectDetectorHelper? = null
    private lateinit var detectorExecutor: ExecutorService
    private lateinit var cameraExecutor: ExecutorService
    
    // UI Elements
    private lateinit var statusTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var debugUserList: TextView
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSignUp: Button
    private lateinit var btnSignIn: Button
    private lateinit var btnIndoor: Button
    private lateinit var btnOutdoor: Button
    private lateinit var btnNavYes: Button
    private lateinit var btnNavNo: Button
    private lateinit var manualInputLayout: View
    private lateinit var modeSelectionLayout: View
    private lateinit var navigationConfirmLayout: View
    private var mapFragment: SupportMapFragment? = null
    private var googleMap: GoogleMap? = null

    // State Management
    enum class AppState {
        INITIALIZING,
        GREETING,
        AUTH_CHOICE,    
        SIGNUP_USERNAME,
        SIGNUP_PASSWORD,
        LOGIN_USERNAME,
        LOGIN_PASSWORD,
        MODE_SELECTION, 
        INDOOR_MODE,
        OUTDOOR_MODE_DESTINATION,
        OUTDOOR_MODE_NAVIGATING,
        OUTDOOR_MODE_CONFIRM_NAVI
    }

    private var currentState = AppState.INITIALIZING
    private val stateHistory = Stack<AppState>()
    private var tempUsername = ""
    private var tempPassword = ""
    private var currentUser: User? = null
    private var lastDestinationLatLng: LatLng? = null
    private var lastAnnouncementTime = 0L
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_main)

            statusTextView = findViewById(R.id.statusTextView)
            previewView = findViewById(R.id.viewFinder)
            debugUserList = findViewById(R.id.debugUserList)
            etUsername = findViewById(R.id.etUsername)
            etPassword = findViewById(R.id.etPassword)
            btnSignUp = findViewById(R.id.btnSignUp)
            btnSignIn = findViewById(R.id.btnSignIn)
            btnIndoor = findViewById(R.id.btnIndoor)
            btnOutdoor = findViewById(R.id.btnOutdoor)
            btnNavYes = findViewById(R.id.btnNavYes)
            btnNavNo = findViewById(R.id.btnNavNo)
            manualInputLayout = findViewById(R.id.manualInputLayout)
            modeSelectionLayout = findViewById(R.id.modeSelectionLayout)
            navigationConfirmLayout = findViewById(R.id.navigationConfirmLayout)
            
            mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
            mapFragment?.let { supportFragmentManager.beginTransaction().hide(it).commit() }

            refreshDebugUserList()

            btnSignUp.setOnClickListener { handleManualSignUp() }
            btnSignIn.setOnClickListener { handleManualSignIn() }
            btnIndoor.setOnClickListener { enterIndoorMode() }
            btnOutdoor.setOnClickListener { enterOutdoorModeRequest() }

            btnNavYes.setOnClickListener {
                lastDestinationLatLng?.let { 
                    speak("Starting navigation.")
                    startNavigationIntent(it) 
                }
                updateState(AppState.OUTDOOR_MODE_NAVIGATING)
            }

            btnNavNo.setOnClickListener {
                speak("Staying in guidance mode.")
                updateState(AppState.OUTDOOR_MODE_NAVIGATING)
            }

            voiceManager = VoiceManager(this) { result -> handleVoiceCommand(result) }
            
            cameraExecutor = Executors.newSingleThreadExecutor()
            detectorExecutor = Executors.newSingleThreadExecutor()
            
            // Lazy-load model in background
            detectorExecutor.execute {
                Log.d("VisionAssist", "Initializing ML Kit detector")
                detectorHelper = ObjectDetectorHelper(this, this)
            }

            checkPermissions()
        } catch (e: Exception) {
            Log.e("VisionAssist", "Crash in onCreate: ${e.message}")
            Toast.makeText(this, "Crash: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDebugUserList() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val users = db.userDao().getAllUsers()
            val userText = if (users.isEmpty()) "No users found" 
                          else users.joinToString("\n") { "${it.username} : ${it.password}" }
            runOnUiThread { debugUserList.text = userText }
        }
    }

    private fun handleManualSignUp() {
        val u = etUsername.text.toString()
        val p = etPassword.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "Please enter both fields", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.userDao().insertUser(User(username = u, password = p))
                refreshDebugUserList()
                currentUser = User(username = u, password = p)
                updateState(AppState.MODE_SELECTION)
                speak("Sign up successful. Welcome. Select a mode.", listen = true)
            } catch (e: Exception) {
                Log.e("VisionAssist", "SignUp Error", e)
                speak("Error signing up.")
            }
        }
    }
    
    private fun handleManualSignIn() {
        val u = etUsername.text.toString()
        val p = etPassword.text.toString()
        if (u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, "Please enter both fields", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val user = db.userDao().getUserByUsername(u)
            if (user != null && user.password == p) {
                currentUser = user
                updateState(AppState.MODE_SELECTION)
                speak("Login successful. Welcome.", listen = true)
            } else {
                Toast.makeText(this@MainActivity, "Invalid credentials", Toast.LENGTH_SHORT).show()
                speak("Invalid credentials.")
            }
        }
    }
    
    private fun enterIndoorMode() {
        updateState(AppState.INDOOR_MODE)
        speak("Indoor Mode active. Say back to return to menu.", listen = true)
    }
    
    private fun enterOutdoorModeRequest() {
        updateState(AppState.OUTDOOR_MODE_DESTINATION)
        speak("Outdoor Mode. Say your destination.", listen = true)
    }

    private fun updateState(newState: AppState, saveToHistory: Boolean = true) {
        if (saveToHistory && currentState != newState) {
            if (currentState != AppState.INITIALIZING && currentState != AppState.GREETING) {
                Log.d("VisionAssist", "Pushing state to history: $currentState")
                stateHistory.push(currentState)
            }
        }
        currentState = newState
        Log.d("VisionAssist", "State transformed to: $newState")
        
        runOnUiThread {
            manualInputLayout.visibility = when (newState) {
                AppState.AUTH_CHOICE, AppState.SIGNUP_USERNAME, AppState.SIGNUP_PASSWORD, 
                AppState.LOGIN_USERNAME, AppState.LOGIN_PASSWORD -> View.VISIBLE
                else -> View.GONE
            }
            modeSelectionLayout.visibility = if (newState == AppState.MODE_SELECTION) View.VISIBLE else View.GONE
            navigationConfirmLayout.visibility = if (newState == AppState.OUTDOOR_MODE_CONFIRM_NAVI) View.VISIBLE else View.GONE
            
            val showMap = when (newState) {
                AppState.OUTDOOR_MODE_DESTINATION, AppState.OUTDOOR_MODE_NAVIGATING, AppState.OUTDOOR_MODE_CONFIRM_NAVI -> true
                else -> false
            }
            mapFragment?.let { if (showMap) supportFragmentManager.beginTransaction().show(it).commit() 
                               else supportFragmentManager.beginTransaction().hide(it).commit() }

            val showCamera = when (newState) {
                AppState.MODE_SELECTION, AppState.INDOOR_MODE, AppState.OUTDOOR_MODE_DESTINATION,
                AppState.OUTDOOR_MODE_NAVIGATING, AppState.OUTDOOR_MODE_CONFIRM_NAVI -> true
                else -> false
            }
            if (showCamera) {
                previewView.visibility = View.VISIBLE
                startCamera()
            } else {
                previewView.visibility = View.GONE
                stopCamera()
            }
            
            statusTextView.text = when (newState) {
                AppState.INITIALIZING -> "Initializing..."
                AppState.GREETING -> "Welcome"
                AppState.AUTH_CHOICE -> "Auth Menu"
                AppState.MODE_SELECTION -> "Selection Menu"
                AppState.INDOOR_MODE -> "Indoor Mode"
                AppState.OUTDOOR_MODE_NAVIGATING -> "Guidance Mode"
                AppState.SIGNUP_USERNAME, AppState.LOGIN_USERNAME -> "Username Input"
                AppState.SIGNUP_PASSWORD, AppState.LOGIN_PASSWORD -> "Password Input"
                else -> statusTextView.text
            }
        }
    }

    private fun goBack() {
        if (stateHistory.isNotEmpty()) {
            val previousState = stateHistory.pop()
            Log.d("VisionAssist", "Popping state from history: $previousState")
            updateState(previousState, saveToHistory = false)
            repromptForState(previousState)
        } else {
            Log.d("VisionAssist", "Already at the menu, cannot go back.")
            speak("Already at the menu.", listen = true)
        }
    }

    private fun repromptForState(state: AppState) {
        val repromptMessage = when (state) {
            AppState.AUTH_CHOICE -> "Going back. Say Sign Up or Sign In."
            AppState.SIGNUP_USERNAME -> "Going back to username input. Please say your username."
            AppState.LOGIN_USERNAME -> "Going back to username input. Please say your username."
            AppState.SIGNUP_PASSWORD -> "Going back to password input. Please say your password."
            AppState.LOGIN_PASSWORD -> "Going back to password input. Please say your password."
            AppState.MODE_SELECTION -> "Returning to selection menu. Say Indoor or Outdoor."
            AppState.OUTDOOR_MODE_DESTINATION -> "Going back. Please say your destination."
            else -> "Going back."
        }
        speak(repromptMessage, listen = true)
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                if (results.all { it.value }) startAppFlow() else Toast.makeText(this, "Permissions needed", Toast.LENGTH_LONG).show()
            }.launch(needed.toTypedArray())
        } else {
            startAppFlow()
        }
    }

    private fun startAppFlow() {
        updateState(AppState.GREETING, saveToHistory = false)
        lifecycleScope.launch {
            delay(1000)
            speak("Welcome to Vision Assist. Say Sign Up or Sign In.", listen = true)
            updateState(AppState.AUTH_CHOICE, saveToHistory = false)
        }
    }

    private fun speak(text: String, listen: Boolean = false) {
        runOnUiThread { statusTextView.text = text }
        voiceManager.speak(text)
        if (listen) {
            lifecycleScope.launch {
                delay(3500)
                voiceManager.startListening()
            }
        }
    }

    private fun handleVoiceCommand(text: String) {
        val command = text.lowercase().trim().removeSuffix(".")
        Log.d("VisionAssist", "Voice Command received: $command")

        if (command.contains("back") || command.contains("return") || 
            command.contains("previous") || command.contains("exit")) {
            goBack()
            return
        }

        if (command.startsWith("stt_error_")) {
            if (currentState == AppState.INDOOR_MODE || 
                currentState == AppState.OUTDOOR_MODE_NAVIGATING) {
                voiceManager.startListening()
            }
            return
        }

        when (currentState) {
            AppState.AUTH_CHOICE -> {
                if (command.contains("sign up")) { updateState(AppState.SIGNUP_USERNAME); speak("Please say your username.", listen = true) }
                else if (command.contains("sign in")) { updateState(AppState.LOGIN_USERNAME); speak("Please say your username.", listen = true) }
                else speak("Please say Sign Up or Sign In.", listen = true)
            }
            AppState.SIGNUP_USERNAME -> { tempUsername = command; updateState(AppState.SIGNUP_PASSWORD); speak("Please say your password.", listen = true) }
            AppState.SIGNUP_PASSWORD -> {
                tempPassword = command
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.userDao().insertUser(User(username = tempUsername, password = tempPassword))
                    refreshDebugUserList()
                    speak("Signup successful. Say Sign in to enter your credentials.", listen = true)
                    updateState(AppState.AUTH_CHOICE)
                }
            }
            AppState.LOGIN_USERNAME -> { tempUsername = command; updateState(AppState.LOGIN_PASSWORD); speak("Please say your password.", listen = true) }
            AppState.LOGIN_PASSWORD -> {
                tempPassword = command
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val user = db.userDao().getUserByUsername(tempUsername)
                    if (user != null && user.password == tempPassword) { currentUser = user; updateState(AppState.MODE_SELECTION); speak("Login successful. Select Indoor or Outdoor.", listen = true) }
                    else { updateState(AppState.AUTH_CHOICE); speak("Invalid credentials. Try saying Sign In again.", listen = true) }
                }
            }
            AppState.MODE_SELECTION -> {
                if (command.contains("indoor")) enterIndoorMode()
                else if (command.contains("outdoor")) enterOutdoorModeRequest()
                else speak("Say Indoor or Outdoor.", listen = true)
            }
            AppState.OUTDOOR_MODE_DESTINATION -> { startOutdoorMode(command) }
            AppState.OUTDOOR_MODE_NAVIGATING, AppState.OUTDOOR_MODE_CONFIRM_NAVI -> {
                if (command.contains("yes") || command.contains("start")) { lastDestinationLatLng?.let { speak("Starting navigation."); startNavigationIntent(it) }; updateState(AppState.OUTDOOR_MODE_NAVIGATING) }
                else if (command.contains("no") || command.contains("stop")) { speak("Staying in guidance mode."); updateState(AppState.OUTDOOR_MODE_NAVIGATING) }
            }
            else -> speak("I didn't understand. Please repeat.", listen = true)
        }
    }
    
    private fun startOutdoorMode(destination: String) {
        updateState(AppState.OUTDOOR_MODE_NAVIGATING)
        speak("Searching for $destination.")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@MainActivity)
                val addresses = geocoder.getFromLocationName(destination, 1)
                if (!addresses.isNullOrEmpty()) {
                    val latLng = LatLng(addresses[0].latitude, addresses[0].longitude)
                    withContext(Dispatchers.Main) {
                        lastDestinationLatLng = latLng
                        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 18f))
                        googleMap?.addMarker(MarkerOptions().position(latLng).title(destination))
                        updateState(AppState.OUTDOOR_MODE_CONFIRM_NAVI)
                        speak("Destination found. Start navigation?", listen = true)
                    }
                } else { withContext(Dispatchers.Main) { speak("Sorry, I couldn't find $destination. Try another place.") } }
            } catch (e: Exception) { Log.e("VisionAssist", "Geocoding failed", e) }
        }
        mapFragment?.getMapAsync(this)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { image ->
                            analyzeImage(image)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, 
                    CameraSelector.DEFAULT_BACK_CAMERA, 
                    preview, 
                    imageAnalyzer
                )
            } catch (exc: Exception) { Log.e("VisionAssist", "Use case binding failed", exc) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(image: ImageProxy) {
        detectorHelper?.detect(image)
    }

    override fun onResults(results: List<ImageLabel>) {
        if (results.isEmpty()) {
            return
        }

        // Implementation of 'Best Label' logic from user suggestion
        val bestLabel = results.maxByOrNull { it.confidence }
        
        val label = bestLabel?.text ?: "Object"
        val score = bestLabel?.confidence ?: 0.0f
        
        Log.d("VisionAssist", "onResults detected: $label ($score) | State: $currentState")

        if (score > 0.40f) {
            val currentTime = System.currentTimeMillis()
            
            runOnUiThread {
                val isActiveMode = when (currentState) {
                    AppState.INDOOR_MODE, 
                    AppState.OUTDOOR_MODE_NAVIGATING,
                    AppState.MODE_SELECTION,
                    AppState.OUTDOOR_MODE_CONFIRM_NAVI -> true
                    else -> false
                }

                if (isActiveMode) {
                    val message = "Detected $label"
                    statusTextView.text = message
                    
                    if (currentTime - lastAnnouncementTime > 4000) {
                        Log.d("VisionAssist", "Speaking detection: $message")
                        voiceManager.speak(message)
                        lastAnnouncementTime = currentTime
                    }
                }
            }
        }
    }

    override fun onError(error: String) {
        Log.e("VisionAssist", "Detector Error: $error")
    }

    private fun stopCamera() {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({ cameraProviderFuture.get().unbindAll() }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("VisionAssist", "Stop Camera failed", e)
        }
    }

    private fun startNavigationIntent(latLng: LatLng) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${latLng.latitude},${latLng.longitude}"))
        intent.setPackage("com.google.android.apps.maps")
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) googleMap?.isMyLocationEnabled = true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
        cameraExecutor.shutdown()
        detectorExecutor.shutdown()
    }
}
