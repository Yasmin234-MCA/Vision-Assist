package com.visionassist.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class VoiceManager(private val context: Context, private val onResult: (String) -> Unit) : 
    TextToSpeech.OnInitListener, RecognitionListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isTtsReady = false

    init {
        tts = TextToSpeech(context, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("VoiceManager", "Language not supported")
            } else {
                isTtsReady = true
            }
        } else {
            Log.e("VoiceManager", "TTS Initialization failed")
        }
    }

    fun speak(text: String, listenAfter: Boolean = false) {
        if (isTtsReady) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ID")
            
            // If we need to listen after speaking, we can use a small delay or OnUtteranceProgressListener
            // For simplicity, we'll assume the caller manages the flow or we use a basic delay mechanism via handler in Activity
            // But to keep it robust:
            if (listenAfter) {
                // In a real app, use OnUtteranceProgressListener to start listening EXACTLY when speaking ends.
                // For this MVP, we will rely on the caller to call startListening() when appropriate 
                // OR we could attach a listener here. Let's make it simple for now.
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // Needs to run on main thread
                        if (context is android.app.Activity) {
                            context.runOnUiThread { startListening() }
                        }
                    }
                    override fun onError(utteranceId: String?) {}
                })
            }
        }    }

    fun startListening() {
        if (isListening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e("VoiceManager", "Error starting listener: ${e.message}")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }

    // RecognitionListener methods
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
    }
    
    override fun onError(error: Int) {
        isListening = false
        Log.e("VoiceManager", "Speech Error: $error")
        // Pass a special error token to allow the Activity to decide whether to restart
        onResult("STT_ERROR_$error")
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val text = matches[0]
            onResult(text)
        } else {
            onResult("STT_ERROR_NO_MATCH")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
