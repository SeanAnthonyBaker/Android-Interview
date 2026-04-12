package com.tulkah.arlivebriefinterview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility to generate a PDF document from an interview transcript.
 */
object PdfGenerator {
    private const val TAG = "PdfGenerator"

    fun generateInterviewPdf(context: Context, transcript: String, fileName: String): File? {
        val pdfDocument = PdfDocument()
        
        // A4 Page Size: 595 x 842 points
        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        
        val titlePaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 20f
            color = Color.BLACK
        }
        
        val textPaint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = 12f
            color = Color.DKGRAY
        }

        val speakerPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 13f
            color = Color.rgb(0, 102, 204) // Professional blue
        }

        var x = 50f
        var y = 60f
        val margin = 50f
        val pageWidth = pageInfo.pageWidth - (2 * margin)

        // Header
        canvas.drawText("Interview Transcript", x, y, titlePaint)
        y += 15f
        canvas.drawLine(x, y, x + pageWidth, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 1f })
        y += 40f

        val paragraphs = transcript.split("\n\n")
        for (para in paragraphs) {
            val lines = para.split("\n")
            for (line in lines) {
                // Check for page overflow
                if (y > pageInfo.pageHeight - margin) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = margin + 20f
                }

                if (line.startsWith("[") && line.contains("]")) {
                    canvas.drawText(line, x, y, speakerPaint)
                    y += 20f
                } else {
                    // Simple word wrap
                    val words = line.split(" ")
                    var currentLine = ""
                    for (word in words) {
                        val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                        if (textPaint.measureText(testLine) > pageWidth) {
                            canvas.drawText(currentLine, x, y, textPaint)
                            y += 18f
                            currentLine = word
                            
                            // Check for overflow inside wrap
                            if (y > pageInfo.pageHeight - margin) {
                                pdfDocument.finishPage(page)
                                pageNumber++
                                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                page = pdfDocument.startPage(pageInfo)
                                canvas = page.canvas
                                y = margin + 20f
                            }
                        } else {
                            currentLine = testLine
                        }
                    }
                    if (currentLine.isNotEmpty()) {
                        canvas.drawText(currentLine, x, y, textPaint)
                        y += 18f
                    }
                }
            }
            y += 15f // Space between turns
        }

        pdfDocument.finishPage(page)

        // Storage location: App Documents folder
        val pdfFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        return try {
            FileOutputStream(pdfFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            Log.d(TAG, "Successfully generated PDF: ${pdfFile.absolutePath}")
            pdfFile
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write PDF", e)
            null
        } finally {
            pdfDocument.close()
        }
    }
}
