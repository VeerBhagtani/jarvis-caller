package com.jarviscaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarviscaller.databinding.ActivityMainBinding
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var contacts: List<ContactEntry> = emptyList()
    private var awaitingConfirmation = false
    private var lastCandidates: List<MatchResult> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = REQUIRED_PERMISSIONS.all { result[it] == true || hasPermission(it) }
        if (granted) {
            initializeAssistant()
        } else {
            updateUi("Permissions needed", "Microphone, contacts, and phone permissions are required.")
            speak("Please enable microphone, contacts, and phone permissions to continue.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.suggestionList.setOnItemClickListener { _, _, position, _ ->
            lastCandidates.getOrNull(position)?.contact?.let { placeCall(it) }
        }
        tts = TextToSpeech(this, this)
        requestPermissionsIfNeeded()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(1.0f)
            if (allPermissionsGranted()) initializeAssistant()
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (allPermissionsGranted()) initializeAssistant()
        else permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun initializeAssistant() {
        contacts = loadContacts()
        updateUi("Jarvis Caller", "Ready to listen")
        promptWhoToCall()
    }

    private fun promptWhoToCall() {
        awaitingConfirmation = false
        binding.suggestionList.visibility = android.view.View.GONE
        updateUi("Who do you want to call?", "Listening...")
        speak("Who do you want to call?") { startListening() }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateUi("Speech unavailable", "Speech recognition is not available on this device.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { updateUi(binding.statusText.text.toString(), "I am listening") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateUi(binding.statusText.text.toString(), "Processing...") }
            override fun onError(error: Int) {
                val msg = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    "I didn't catch that. Please say the name again."
                else "Speech recognition had an issue. Please try again."
                updateUi("Try again", msg)
                speak(msg) { startListening() }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val spoken = matches.firstOrNull().orEmpty()
                if (spoken.isBlank()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
                handleSpeech(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun handleSpeech(spoken: String) {
        updateUi(spoken, if (awaitingConfirmation) "Confirming match" else "Matching contact")
        if (awaitingConfirmation) {
            val selected = resolveConfirmation(spoken)
            if (selected != null) placeCall(selected)
            else { speak("I still couldn't confirm. Please tap a result on screen."); showSuggestions(lastCandidates) }
            return
        }
        val cleaned = spoken.removePrefixIgnoreCase("call ").trim()
        val ranked = rankContacts(cleaned)
        lastCandidates = ranked.take(5)
        when {
            ranked.isEmpty() -> {
                updateUi("No match found", "Try saying the name again")
                speak("I couldn't find that contact. Please say the name again.") { startListening() }
            }
            ranked.size == 1 || ranked[0].score >= 0.86 -> placeCall(ranked[0].contact)
            ranked.size >= 2 && ranked[0].score >= 0.60 -> {
                awaitingConfirmation = true
                val top = ranked.take(3)
                lastCandidates = top
                showSuggestions(top)
                val prompt = "Did you mean " + top.joinToString(" or ") { it.contact.displayName } + "?"
                speak(prompt) { startListening() }
            }
            else -> {
                showSuggestions(ranked.take(8))
                speak("I found multiple results. Please tap the right contact on screen.")
            }
        }
    }

    private fun resolveConfirmation(spoken: String): ContactEntry? {
        val normalized = normalize(spoken)
        val ordinalWords = listOf("first", "second", "third")
        lastCandidates.forEachIndexed { index, match ->
            val nameNorm = normalize(match.contact.displayName)
            if (normalized.contains(nameNorm) || nameNorm.contains(normalized)) return match.contact
            if (ordinalWords.getOrNull(index) != null && normalized.contains(ordinalWords[index])) return match.contact
        }
        return null
    }

    private fun showSuggestions(results: List<MatchResult>) {
        val lines = results.map { "${it.contact.displayName}  \u2022  ${it.contact.phoneNumber}" }
        binding.suggestionList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        binding.suggestionList.visibility = android.view.View.VISIBLE
        updateUi(binding.statusText.text.toString(), "Tap a contact to call")
    }

    private fun placeCall(contact: ContactEntry) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) { permissionLauncher.launch(REQUIRED_PERMISSIONS); return }
        val phrase = "Calling ${contact.displayName}"
        updateUi(phrase, contact.phoneNumber)
        speak(phrase) {
            val intent = Intent(Intent.ACTION_CALL).apply { data = Uri.parse("tel:${Uri.encode(contact.phoneNumber)}") }
            try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, "Unable to place call.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun loadContacts(): List<ContactEntry> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()
        val recencyMap = loadCallSignals()
        val results = mutableListOf<ContactEntry>()
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )
        cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue
                val id = it.getLong(idIndex)
                val key = number.filter(Char::isDigit)
                val signal = recencyMap[key] ?: CallSignal()
                results.add(ContactEntry(id, name, number, signal.callCount, signal.lastCallEpoch))
            }
        }
        return results.distinctBy { "${it.displayName}|${it.phoneNumber}" }
    }

    private fun loadCallSignals(): Map<String, CallSignal> {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyMap()
        val map = mutableMapOf<String, CallSignal>()
        val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC")
        cursor?.use {
            val numberIndex = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateIndex = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            while (it.moveToNext()) {
                val number = it.getString(numberIndex)?.filter(Char::isDigit).orEmpty()
                if (number.isBlank()) continue
                val date = it.getLong(dateIndex)
                val existing = map[number]
                map[number] = if (existing == null) CallSignal(1, date)
                              else existing.copy(callCount = existing.callCount + 1, lastCallEpoch = max(existing.lastCallEpoch, date))
            }
        }
        return map
    }

    private fun rankContacts(query: String): List<MatchResult> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        return contacts.map { contact ->
            val normalizedName = normalize(contact.displayName)
            val nameScore = stringSimilarity(normalizedQuery, normalizedName)
            val tokenBoost = if (normalizedName.split(" ").any { it.startsWith(normalizedQuery) }) 0.15 else 0.0
            val containsBoost = if (normalizedName.contains(normalizedQuery)) 0.20 else 0.0
            val freqScore = (contact.callCount.coerceAtMost(20) / 20.0) * 0.10
            val recencyScore = if (contact.lastCalledAt > 0L) {
                val ageDays = ((now - contact.lastCalledAt).coerceAtLeast(0L) / (1000.0 * 60 * 60 * 24))
                (1.0 / (1.0 + ageDays / 14.0)) * 0.10
            } else 0.0
            val total = (nameScore * 0.70) + tokenBoost + containsBoost + freqScore + recencyScore
            MatchResult(contact, total.coerceAtMost(1.0))
        }.filter { it.score >= 0.32 }.sortedByDescending { it.score }
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / max(a.length, b.length).toDouble())
    }

    private fun levenshtein(lhs: String, rhs: String): Int {
        val dp = IntArray(rhs.length + 1) { it }
        for (i in 1..lhs.length) {
            var prev = i - 1; dp[0] = i
            for (j in 1..rhs.length) {
                val temp = dp[j]
                val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[rhs.length]
    }

    private fun normalize(value: String): String {
        val withoutAccents = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return withoutAccents.replace("[^a-z0-9 ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun updateUi(status: String, detail: String) {
        binding.statusText.text = status
        binding.detailText.text = detail
    }

    private fun speak(message: String, onDone: (() -> Unit)? = null) {
        val utteranceId = System.nanoTime().toString()
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.let { handler.post(it) } }
            override fun onError(utteranceId: String?) { onDone?.let { handler.post(it) } }
        })
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH,
            Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }, utteranceId)
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all(::hasPermission)
    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG
        )
    }
}

data class ContactEntry(val id: Long, val displayName: String, val phoneNumber: String, val callCount: Int = 0, val lastCalledAt: Long = 0L)
data class MatchResult(val contact: ContactEntry, val score: Double)
data class CallSignal(val callCount: Int = 0, val lastCallEpoch: Long = 0L)
