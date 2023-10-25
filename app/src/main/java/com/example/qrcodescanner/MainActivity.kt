package com.example.qrcodescanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var previewView: PreviewView
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var cameraProvider: ProcessCameraProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up the view where the camera preview will be displayed
        previewView = findViewById(R.id.previewView)

        // Check if the app has the necessary permissions, otherwise request them
        if (allPermissionsGranted()) {
            startCamera() // Initialize the camera if permissions are granted
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    // Function to check if all required permissions have been granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // Function to set up and start the camera
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                // Retrieve an instance of ProcessCameraProvider, which binds the lifecycle of cameras to the lifecycle owner
                cameraProvider = cameraProviderFuture.get()
                // Build a viewfinder use case to preview the camera image
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // Select the back camera for our use case
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    // Unbind all use cases before rebinding
                    cameraProvider.unbindAll()
                    // Bind use cases to the camera
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview)
                    setupBarcodeScanner() // Set up barcode scanner
                } catch (e: Exception) {
                    Log.e("MainActivity", "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(this)
        )
    }

    // Annotation indicates that the ImageAnalysis use case operates with the experimental get image mode
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun setupBarcodeScanner() {
        // Set up the barcode scanner options for QR codes
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        // Get an instance of BarcodeScanning to process the images
        val barcodeScanner = BarcodeScanning.getClient(options)

        // Build an image analysis use case and choose the analysis strategy
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // Set the analyzer for the image analysis use case
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            val inputImage = InputImage.fromMediaImage(imageProxy.image!!, imageProxy.imageInfo.rotationDegrees)
            // Process the image and scan for barcodes
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    // Iterate through each barcode detected and handle it depending on its type
                    for (barcode in barcodes) {
                        when (barcode.valueType) {
                            Barcode.TYPE_TEXT -> {
                                // If it's a plain text, display it using a toast
                                barcode.rawValue?.let { rawValue ->
                                    Toast.makeText(this, rawValue, Toast.LENGTH_LONG).show()
                                }
                            }
                            Barcode.TYPE_WIFI -> {
                                // If it's a WiFi connection info, attempt to connect to the WiFi
                                barcode.wifi?.ssid?.let { ssid ->
                                    barcode.wifi?.password?.let { password ->
                                        val type = barcode.wifi!!.encryptionType
                                        connectToWifi(ssid, password, type)
                                    }
                                }
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    // Log errors if barcode scanning fails
                    Log.e("MainActivity", "Barcode analysis failure", it)
                }
                .addOnCompleteListener {
                    // Close the image when analysis is complete
                    imageProxy.close()
                }
        }

        // Bind the image analysis use case to the camera
        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
    }

    // Handle the results of the permission request
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera() // Permissions granted, initialize the camera
            } else {
                // Inform the user that permissions were not granted and close the app
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // Function to connect to a specified WiFi network
    private fun connectToWifi(ssid: String, password: String, type: Int) {
        // Use a coroutine to move the WiFi connection process off the main thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectToWifiAndroid10AndAbove(ssid, password, type)
                } else {
                    connectToWifiBelowAndroid10(ssid, password, type)
                }
                // Switch to the Main thread to update any UI or inform the user
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connected to $ssid", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Log or handle exceptions related to WiFi connection
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to connect to $ssid", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectToWifiAndroid10AndAbove(ssid: String, password: String, type: Int) {
        val wifiNetworkSpecifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(wifiNetworkSpecifier)
            .build()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            // you can implement callback functions here to monitor the status
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback)
        // Use connectivityManager.bindProcessToNetwork() or connectivityManager.registerNetworkCallback() for a persistent connection
    }

    private fun connectToWifiBelowAndroid10(ssid: String, password: String, type: Int) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wifiConfig = WifiConfiguration()
        wifiConfig.SSID = String.format("\"%s\"", ssid)
        wifiConfig.preSharedKey = String.format("\"%s\"", password)

        val netId = wifiManager.addNetwork(wifiConfig)
        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()
    }
}
