package com.tulkah.arlivebriefinterview

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.tulkah.arlivebriefinterview.ui.theme.ARLiveBriefInterviewTheme
import io.github.sceneview.ar.ARScene
import kotlinx.coroutines.delay

// --- Data Models ---

data class BriefSection(
    val title: String,
    val timeGuide: String,
    val topics: List<String>,
    val technique: String = ""
)

// --- Activity ---

class ARInterviewActivity : ComponentActivity() {

    private lateinit var geminiLive: GeminiLiveService
    private lateinit var audioRecorder: AudioRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val briefText = intent.getStringExtra("EXTRA_BRIEF_TEXT") ?: ""
        val sections = parseBriefIntoSections(briefText)

        geminiLive = GeminiLiveService(applicationContext)
        audioRecorder = AudioRecorder(this)

        // Read API key from assets
        val apiKey = try {
            assets.open("api_key.txt").bufferedReader().readText().trim()
        } catch (e: Exception) {
            ""
        }

        setContent {
            ARLiveBriefInterviewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GeminiLiveHUD(
                        sections = sections,
                        briefText = briefText,
                        apiKey = apiKey,
                        geminiLive = geminiLive,
                        audioRecorder = audioRecorder,
                        onSessionEnd = {
                            val transcriptEntries = geminiLive.transcript.value
                            val fullTranscript = transcriptEntries.joinToString("\n\n") { entry ->
                                val speakerLabel = when (entry.speaker) {
                                    "ai" -> "PHIL SAGE (INTERVIEWER)"
                                    "user" -> "STAKEHOLDER"
                                    else -> entry.speaker.uppercase()
                                }
                                "[$speakerLabel]\n${entry.text}"
                            }

                            val file = java.io.File(cacheDir, "interview_session.m4a")
                            val resultIntent = Intent().apply {
                                putExtra("RESULT_AUDIO_PATH", file.absolutePath)
                                putExtra("RESULT_TRANSCRIPT", fullTranscript)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }

    /**
     * Dynamically parses the brief into sections for the reference sidebar.
     */
    private fun parseBriefIntoSections(brief: String): List<BriefSection> {
        if (brief.isBlank()) return listOf(
            BriefSection("No Brief Loaded", "", listOf("Load a brief before starting"), "")
        )

        val sections = mutableListOf<BriefSection>()
        val lines = brief.lines().map { it.trim() }.filter { it.isNotBlank() }

        val metaLines = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()
        var foundFirstSection = false

        for (line in lines) {
            if (!foundFirstSection && line.matches(Regex("^\\d+\\.\\s+.*"))) {
                foundFirstSection = true
            }
            if (foundFirstSection) bodyLines.add(line) else metaLines.add(line)
        }

        if (metaLines.isNotEmpty()) {
            sections.add(BriefSection("Context & Setup", "", metaLines.take(8), "Reference only"))
        }

        var currentTitle = ""
        var currentTime = ""
        var currentTopics = mutableListOf<String>()
        var currentTechnique = ""

        for (line in bodyLines) {
            val sectionMatch = Regex("^(\\d+\\.\\s+.+?)(?:\\s*\\((\\d+[–-]\\d+\\s*min(?:s|utes)?)\\))?$").find(line)
            val subSectionMatch = Regex("^([A-Z]\\.\\s+.+?)(?:\\s*\\((\\d+[–-]\\d+\\s*min(?:s|utes)?)\\))?$").find(line)

            when {
                sectionMatch != null -> {
                    if (currentTitle.isNotBlank()) {
                        sections.add(BriefSection(currentTitle, currentTime, currentTopics.toList(), currentTechnique))
                    }
                    currentTitle = sectionMatch.groupValues[1].trim()
                    currentTime = sectionMatch.groupValues.getOrElse(2) { "" }.trim()
                    currentTopics = mutableListOf()
                    currentTechnique = ""
                }
                subSectionMatch != null -> {
                    if (currentTitle.isNotBlank()) {
                        sections.add(BriefSection(currentTitle, currentTime, currentTopics.toList(), currentTechnique))
                    }
                    currentTitle = subSectionMatch.groupValues[1].trim()
                    currentTime = subSectionMatch.groupValues.getOrElse(2) { "" }.trim()
                    currentTopics = mutableListOf()
                    currentTechnique = ""
                }
                line.lowercase().let { it.contains("5 whys") || it.contains("probe") || it.contains("listen actively") } -> {
                    currentTechnique = line
                    currentTopics.add(line)
                }
                else -> {
                    val cleaned = line.removePrefix("•").removePrefix("-").removePrefix("▸").removePrefix("*").trim()
                    if (cleaned.isNotBlank()) currentTopics.add(cleaned)
                }
            }
        }
        if (currentTitle.isNotBlank()) {
            sections.add(BriefSection(currentTitle, currentTime, currentTopics.toList(), currentTechnique))
        }
        if (sections.isEmpty()) {
            sections.add(BriefSection("Interview Brief", "", lines.take(15), ""))
        }
        return sections
    }

    override fun onDestroy() {
        super.onDestroy()
        geminiLive.disconnect()
        audioRecorder.stopRecording()
    }
}

// ═══════════════════════════════════════════════════════════════
// GEMINI LIVE HUD — The main AR coaching screen
// ═══════════════════════════════════════════════════════════════

@Composable
fun GeminiLiveHUD(
    sections: List<BriefSection>,
    briefText: String,
    apiKey: String,
    geminiLive: GeminiLiveService,
    audioRecorder: AudioRecorder,
    onSessionEnd: () -> Unit
) {
    val activity = LocalContext.current as ARInterviewActivity

    // Observe Gemini Live state
    val connectionState by geminiLive.connectionState.collectAsState()
    val transcriptEntries by geminiLive.transcript.collectAsState()
    val activeTurn by geminiLive.activeTurnText.collectAsState()
    val isAiSpeaking by geminiLive.isAiSpeaking.collectAsState()

    // Local UI state
    var isSessionActive by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var showBriefPanel by remember { mutableStateOf(false) }
    var currentSectionIndex by remember { mutableStateOf(0) }
    var trackingMessage by remember { mutableStateOf("Initializing AR...") }

    // Timer
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(isSessionActive) {
        while (isSessionActive) {
            delay(1000)
            elapsedSeconds++
        }
    }

    // Auto-scroll transcript
    val listState = rememberLazyListState()
    LaunchedEffect(transcriptEntries.size) {
        if (transcriptEntries.isNotEmpty()) {
            listState.animateScrollToItem(transcriptEntries.size - 1)
        }
    }

    // Colours
    val hudBg = Color.Black.copy(alpha = 0.70f)
    val accentCyan = Color(0xFF00E5FF)
    val accentGreen = Color(0xFF76FF03)
    val accentAmber = Color(0xFFFFD600)
    val recordingRed = Color(0xFFFF1744)
    val geminiPurple = Color(0xFFBB86FC)

    Box(modifier = Modifier.fillMaxSize()) {

        // ── AR Camera Background ──
        ARScene(
            modifier = Modifier.fillMaxSize(),
            sessionConfiguration = { _, config ->
                config.geospatialMode = Config.GeospatialMode.ENABLED
                config.focusMode = Config.FocusMode.AUTO
            },
            onSessionUpdated = { session, _ ->
                val earth = session.earth
                trackingMessage = if (earth?.trackingState == com.google.ar.core.TrackingState.TRACKING) {
                    "● GEO"
                } else {
                    "○ AR"
                }
            }
        )

        // ══════════════════════════════════════
        // TOP BAR: Status + Timer + Connection
        // ══════════════════════════════════════
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(hudBg, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recording dot + label
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) recordingRed else Color.Gray)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isRecording) "REC" else "IDLE",
                    color = if (isRecording) recordingRed else Color.Gray,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }

            // Timer
            Text(
                formatTime(elapsedSeconds),
                color = when {
                    elapsedSeconds > 1500 -> recordingRed
                    elapsedSeconds > 1200 -> accentAmber
                    else -> Color.White
                },
                fontSize = 18.sp, fontWeight = FontWeight.Bold
            )

            // Gemini + AR status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trackingMessage, color = accentGreen, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (connectionState) {
                                GeminiLiveService.ConnectionState.CONNECTED -> accentGreen
                                GeminiLiveService.ConnectionState.CONNECTING -> accentAmber
                                else -> Color.Gray
                            }
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    when (connectionState) {
                        GeminiLiveService.ConnectionState.CONNECTED -> "LIVE"
                        GeminiLiveService.ConnectionState.CONNECTING -> "..."
                        else -> "OFF"
                    },
                    color = geminiPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // ══════════════════════════════════════
        // CENTRE: Gemini coaching messages
        // ══════════════════════════════════════
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .fillMaxHeight(0.55f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isSessionActive) {
                // Pre-session: show start instructions
                Card(
                    colors = CardDefaults.cardColors(containerColor = hudBg),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🎙️ AR Live Brief", color = accentCyan, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Gemini Live will conduct the\ninterview using your brief",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Brief loaded: ${sections.size} sections",
                            color = accentGreen, fontSize = 12.sp)
                        if (apiKey.isBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("⚠️ No API key found — add api_key.txt to assets",
                                color = recordingRed, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // Active session: Live conversation transcript

                // Active turn — what Gemini is currently saying
                if (activeTurn.isNotBlank() || isAiSpeaking) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E).copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎤", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Gemini Speaking", color = geminiPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = geminiPurple,
                                    strokeWidth = 2.dp
                                )
                            }
                            if (activeTurn.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    activeTurn,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Scrollable conversation transcript
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .background(hudBg.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    items(transcriptEntries) { entry ->
                        val (icon, color) = when (entry.speaker) {
                            "ai" -> "🤖" to geminiPurple.copy(alpha = 0.9f)
                            "system" -> "⚙️" to accentAmber
                            else -> "👤" to accentCyan
                        }
                        Row(modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(icon, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                entry.text,
                                color = color,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════
        // BRIEF SIDEBAR — tap "Brief" to show/hide
        // ══════════════════════════════════════
        AnimatedVisibility(
            visible = showBriefPanel,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it })
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = hudBg),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(0.7f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("📋 Brief Sections", color = accentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    // Section tabs
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        sections.forEachIndexed { idx, _ ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .background(
                                        if (idx == currentSectionIndex) accentCyan else Color.White.copy(alpha = 0.3f),
                                        RoundedCornerShape(2.dp)
                                    )
                                    .clickable { currentSectionIndex = idx }
                            )
                        }
                    }

                    val section = sections.getOrNull(currentSectionIndex)
                    if (section != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(section.title, color = accentCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (section.timeGuide.isNotBlank()) {
                            Text(section.timeGuide, color = accentAmber, fontSize = 11.sp)
                        }
                        if (section.technique.isNotBlank()) {
                            Text("💡 ${section.technique}", color = accentGreen, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        section.topics.forEach { topic ->
                            Text("▸ $topic", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { if (currentSectionIndex > 0) currentSectionIndex-- }) {
                            Text("◀ Prev", color = Color.White, fontSize = 11.sp)
                        }
                        TextButton(onClick = { if (currentSectionIndex < sections.size - 1) currentSectionIndex++ }) {
                            Text("Next ▶", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // ══════════════════════════════════════
        // BOTTOM BAR: Controls
        // ══════════════════════════════════════
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(hudBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brief toggle
            Button(
                onClick = { showBriefPanel = !showBriefPanel },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showBriefPanel) accentCyan.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("📋", fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Main action button: Start / End session
            Button(
                onClick = {
                    if (!isSessionActive) {
                        // Start Gemini Live session + recording
                        if (apiKey.isNotBlank()) {
                            geminiLive.connect(apiKey, briefText)
                        }
                        val file = java.io.File(activity.cacheDir, "interview_session.m4a")
                        audioRecorder.startRecording(file)
                        isRecording = true
                        isSessionActive = true
                    } else {
                        // End session
                        audioRecorder.stopRecording()
                        geminiLive.disconnect()
                        isRecording = false
                        isSessionActive = false
                        onSessionEnd()
                    }
                },
                enabled = apiKey.isNotBlank() || isSessionActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSessionActive) recordingRed else accentGreen,
                    contentColor = Color.Black
                ),
                modifier = Modifier.weight(2f)
            ) {
                Text(
                    if (isSessionActive) "■ END SESSION" else "● GO LIVE",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Placeholder for future controls
            Button(
                onClick = { /* future: mark topic done, take note, etc */ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("✅", fontSize = 16.sp)
            }
        }
    }
}

// Format seconds into MM:SS
private fun formatTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}
