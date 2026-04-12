package com.tulkah.arlivebriefinterview

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tulkah.arlivebriefinterview.ui.theme.ARLiveBriefInterviewTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ARLiveBriefInterviewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    var briefText by remember { mutableStateOf("") }
    var resultPath by remember { mutableStateOf<String?>(null) }
    var transcriptResult by remember { mutableStateOf<String?>(null) }
    
    // AR Session Result Launcher
    val arSessionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            resultPath = result.data?.getStringExtra("RESULT_AUDIO_PATH")
            transcriptResult = result.data?.getStringExtra("RESULT_TRANSCRIPT")
            android.util.Log.d("MainActivity", "Session result received. Transcript length: ${transcriptResult?.length ?: 0}")
        }
    }
    
    // Permission Launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            val intent = Intent(context, ARInterviewActivity::class.java).apply {
                putExtra("EXTRA_BRIEF_TEXT", briefText)
            }
            arSessionLauncher.launch(intent)
        } else {
            Toast.makeText(context, "Permissions required for AR Session", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AR Live Brief Interview") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount!! > 0) {
                        val pasteData = clipboard.primaryClip?.getItemAt(0)?.text
                        if (!pasteData.isNullOrEmpty()) {
                            briefText = pasteData.toString()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Paste Interview Brief from Clipboard")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = {
                    try {
                        val inputStream = context.assets.open("brief.txt")
                        briefText = inputStream.bufferedReader().use { it.readText() }
                        android.widget.Toast.makeText(context, "Brief loaded!", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Brief from File")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(
                value = briefText,
                onValueChange = { briefText = it },
                label = { Text("Interview Brief") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp),
                maxLines = 15
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = {
                    if (briefText.trim().isEmpty()) {
                        Toast.makeText(context, "Please paste a brief first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    val requiredPermissions = arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    
                    val hasAll = requiredPermissions.all {
                        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                    }
                    
                    if (hasAll) {
                        val intent = Intent(context, ARInterviewActivity::class.java).apply {
                            putExtra("EXTRA_BRIEF_TEXT", briefText)
                        }
                        arSessionLauncher.launch(intent)
                    } else {
                        permissionsLauncher.launch(requiredPermissions)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start AR Live View Interview Session")
            }
            
            if (resultPath != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Session Complete!", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saved to: $resultPath", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { 
                                transcriptResult?.let { text ->
                                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                    val pdfFile = PdfGenerator.generateInterviewPdf(context, text, "Interview_Script_$timestamp.pdf")
                                    if (pdfFile != null && pdfFile.exists()) {
                                        sharePdf(context, pdfFile)
                                    } else {
                                        Toast.makeText(context, "❌ Error generating PDF", Toast.LENGTH_SHORT).show()
                                    }
                                } ?: run {
                                    Toast.makeText(context, "No transcript available", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Generate Script PDF")
                        }
                    }
                }
            }
        }
    }
}

private fun sharePdf(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open Interview Script"))
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error sharing PDF", e)
        android.widget.Toast.makeText(context, "Could not open PDF: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
