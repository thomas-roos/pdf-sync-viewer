package com.github.blebrowserbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import com.github.blebrowserbridge.databinding.ActivityMainBinding
import java.io.IOException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothController: BluetoothController

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageIndex = 0

    private var pdfFiles: List<Uri> = emptyList()
    private var currentPdfIndex = -1
    private var isServer = false

    private lateinit var gestureDetector: GestureDetector

    private val tag = "BLE_PDF_SYNC"

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach { (permission, isGranted) ->
            if (!isGranted) {
                Log.w(tag, "Permission not granted: $permission")
                Toast.makeText(this, "Permission required: $permission", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(tag, "Folder selected: $uri")
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                listPdfFilesInFolder(uri)
                if (pdfFiles.isNotEmpty()) {
                    currentPdfIndex = 0
                    openPdf(pdfFiles[currentPdfIndex])
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothController = BluetoothController(this)
        
        bluetoothController.onPdfNameReceived = { pdfName, pageIndex ->
            runOnUiThread {
                if (!isServer) {
                    if (pdfName == "server-ready") {
                        binding.receivedPageText.text = getString(R.string.server_ready)
                        return@runOnUiThread
                    }

                    Log.d(tag, "Client received PDF: $pdfName, page: $pageIndex")
                    // Match by the start of the name to handle truncated advertisement data
                    val uriToOpen = pdfFiles.find { uri -> 
                        getFileName(uri)?.startsWith(pdfName, ignoreCase = true) == true 
                    }

                    if (uriToOpen != null) {
                        val newPdfIndex = pdfFiles.indexOf(uriToOpen)
                        if (newPdfIndex != currentPdfIndex) {
                            currentPdfIndex = newPdfIndex
                            loadPDF(uriToOpen)
                        }
                        renderPage(pageIndex)
                    } else {
                        Log.w(tag, "No local PDF found starting with: $pdfName")
                        binding.pdfImageView.isVisible = false
                        binding.receivedPageText.isVisible = true
                        // Display the received name even if not found locally
                        binding.receivedPageText.text = getString(R.string.incoming_pdf, pdfName)
                        binding.readingPageInfo.text = getString(R.string.missing_pdf, pdfName)
                        Toast.makeText(this, "File '$pdfName' not found locally", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        initBluetooth()
        setupUI()
        setupGestures()
        requestPermissions()

        Log.d(tag, "App started")
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 100
            private val swipeVelocityThreshold = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                        if (diffX > 0) {
                            navigatePrevPage()
                        } else {
                            navigateNextPage()
                        }
                        return true
                    }
                } else if (diffY > swipeThreshold && abs(velocityY) > swipeVelocityThreshold) {
                    // Swipe Down from top
                    showPdfSelectionMenu()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleFullScreen()
                return true
            }
        })

        val touchListener = View.OnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            gestureDetector.onTouchEvent(event)
        }

        binding.pdfImageView.setOnTouchListener(touchListener)
        binding.receivedPageText.setOnTouchListener(touchListener)
        binding.pdfContainer.setOnTouchListener(touchListener)
    }

    private fun initBluetooth() {
        if (!bluetoothController.isBluetoothEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            Log.w(tag, "Bluetooth not enabled")
        } else {
            Log.d(tag, "Bluetooth initialized successfully")
        }
    }

    private fun setupUI() {
        binding.selectPdfButton.setOnClickListener {
            selectPdfFolder()
        }

        binding.startServerButton.setOnClickListener {
            startBLEServer()
        }

        binding.startClientButton.setOnClickListener {
            startBLEClient()
        }

        binding.debugButton.setOnClickListener {
            showDebugLog()
        }

        binding.pdfMenuButton.setOnClickListener { showPdfSelectionMenu() }

        binding.prevPageButton.setOnClickListener { navigatePrevPage() }
        binding.nextPageButton.setOnClickListener { navigateNextPage() }
    }

    private fun navigatePrevPage() {
        if (currentPageIndex > 0) {
            renderPage(currentPageIndex - 1)
        } else {
            navigatePreviousPdf()
        }
    }

    private fun navigateNextPage() {
        pdfRenderer?.let {
            if (currentPageIndex < it.pageCount - 1) {
                renderPage(currentPageIndex + 1)
            } else {
                navigateNextPdf()
            }
        }
    }

    private fun navigatePreviousPdf() {
        if (currentPdfIndex > 0) {
            currentPdfIndex--
            openPdf(pdfFiles[currentPdfIndex])
        } else {
            Toast.makeText(this, "First PDF in folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateNextPdf() {
        if (currentPdfIndex < pdfFiles.size - 1) {
            currentPdfIndex++
            openPdf(pdfFiles[currentPdfIndex])
        } else {
            Toast.makeText(this, "Last PDF in folder", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPdfSelectionMenu() {
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "No PDFs found in folder", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = pdfFiles.map { getFileName(it) ?: "Unknown" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select PDF")
            .setItems(fileNames) { _, which ->
                currentPdfIndex = which
                openPdf(pdfFiles[which])
            }
            .show()
    }

    private fun toggleFullScreen() {
        if (binding.setupControls.isVisible) {
            if (pdfFiles.isNotEmpty()) {
                binding.setupControls.isVisible = false
                binding.readingControls.isVisible = true
                hideSystemUI()
            }
            return
        }

        val isOverlayVisible = binding.readingControls.isVisible
        if (isOverlayVisible) {
            binding.readingControls.isVisible = false
            hideSystemUI()
        } else {
            binding.readingControls.isVisible = true
            showSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            val controller = window.insetsController
            controller?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun selectPdfFolder() {
        Log.d(tag, "Selecting PDF folder")
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        pickPdfLauncher.launch(intent)
    }

    private fun listPdfFilesInFolder(folderUri: Uri) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri))
        val cursor = contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)

        val pdfs = mutableListOf<Uri>()
        cursor?.use {
            while (it.moveToNext()) {
                val mimeType = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                if (mimeType == "application/pdf") {
                    val docId = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                    pdfs.add(fileUri)
                }
            }
        }
        pdfFiles = pdfs.sortedBy { getFileName(it) } // Sort files alphabetically
        Log.d(tag, "Found ${pdfFiles.size} PDF files in the folder")
    }

    private fun openPdf(uri: Uri) {
        val pdfName = getFileName(uri)
        if (pdfName != null) {
            loadPDF(uri)
            renderPage(0)
        } else {
            Toast.makeText(this, "Could not get PDF name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startBLEServer() {
        Log.d(tag, "Starting BLE Server")
        isServer = true
        bluetoothController.startServer()
        binding.statusText.text = getString(R.string.status_server_started)
        
        if (pdfRenderer != null) {
            binding.setupControls.isVisible = false
            binding.readingControls.isVisible = true
            hideSystemUI()
        }
        
        Toast.makeText(this, "BLE Server Started", Toast.LENGTH_SHORT).show()
    }

    private fun startBLEClient() {
        if (pdfFiles.isEmpty()) {
            Toast.makeText(this, "Please select a PDF folder on this device first", Toast.LENGTH_LONG).show()
            return
        }
        Log.d(tag, "Starting BLE Client")
        isServer = false
        bluetoothController.startClient()
        binding.statusText.text = getString(R.string.status_client_started)
        binding.pdfImageView.isVisible = false
        binding.receivedPageText.isVisible = true
        binding.receivedPageText.text = getString(R.string.client_waiting)
        
        binding.setupControls.isVisible = false
        binding.readingControls.isVisible = true
        hideSystemUI()

        Toast.makeText(this, "BLE Client Started", Toast.LENGTH_SHORT).show()
    }


    private fun loadPDF(uri: Uri) {
        Log.d(tag, "Loading PDF: $uri")
        try {
            val fileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return
            currentPage?.close()
            currentPage = null
            pdfRenderer?.close()
            pdfRenderer = PdfRenderer(fileDescriptor)
            Log.d(tag, "PDF loaded: ${pdfRenderer?.pageCount} pages")
        } catch (e: Exception) {
            Log.e(tag, "Error loading PDF", e)
            Toast.makeText(this, "Error loading PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return

        try {
            currentPage?.close()
            val page = renderer.openPage(pageIndex)
            currentPage = page
            currentPageIndex = pageIndex

            val bitmap = createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            runOnUiThread {
                binding.pdfImageView.setImageBitmap(bitmap)
                binding.pdfImageView.isVisible = true
                binding.receivedPageText.isVisible = false
                
                val fileName = currentPdfIndex.takeIf { it >= 0 }?.let { getFileName(pdfFiles[it]) } ?: "Unknown"
                binding.readingPageInfo.text = getString(R.string.page_info, fileName, pageIndex + 1, renderer.pageCount)
                
                if (isServer) {
                    bluetoothController.sendPdfNameViaAdvertisement(fileName, pageIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error rendering page", e)
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
    
    private fun showDebugLog() {
        val log = bluetoothController.bleEvents.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("BLE Debug Log")
            .setMessage(log)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }

        val permissionsNotGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNotGranted.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsNotGranted.toTypedArray())
            Log.d(tag, "Requesting permissions: $permissionsNotGranted")
        } else {
            Log.d(tag, "All necessary permissions are already granted")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
        bluetoothController.stop()
        Log.d(tag, "App destroyed")
    }
}
