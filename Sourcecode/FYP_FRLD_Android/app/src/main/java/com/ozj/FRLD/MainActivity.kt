package com.ozj.FRLD

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.volley.AuthFailureError
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import java.io.*


class MainActivity : AppCompatActivity() {

    lateinit var bitmap:Bitmap
    lateinit var cameraDevice: CameraDevice
    lateinit var handler:Handler
    lateinit var  cameraManager: CameraManager
    lateinit var textureView: TextureView

    val BASE_URL = "http://127.0.0.1:5000"
    //val BASE_URL = "http://10.123.51.59:10078" // in case test connect wireless

    var loopingValue = 0

    override fun onCreate(savedInstanceState: Bundle?) {


        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        getPermission()
        // ping to make sure phone connected to api server
        //ping()
        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            findViewById<TextView>(R.id.resultShake).text = "Pending"
            findViewById<TextView>(R.id.resultNod).text = "Pending"
            findViewById<TextView>(R.id.resultBlink).text = "Pending"
            findViewById<TextView>(R.id.resultSmile).text = "Pending"
            findViewById<TextView>(R.id.resultMouth).text = "Pending"
            findViewById<TextView>(R.id.resultIdentity).text = "Pending"
        }

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        textureView = findViewById<TextureView>(R.id.textureView)


        textureView.surfaceTextureListener = object:TextureView.SurfaceTextureListener{
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {

            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {

                if (loopingValue == 50) {
                    bitmap = textureView.bitmap!!
                    startDetection(bitmap)
                    loopingValue = 0
                }
                loopingValue += 1
            }

        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    }

    var indicator = 0
    var lastResult = ""

    private fun startDetection(bitmap: Bitmap) {

        var resultShake = findViewById<TextView>(R.id.resultShake).text.toString()
        var resultNod = findViewById<TextView>(R.id.resultNod).text.toString()
        var resultBlink = findViewById<TextView>(R.id.resultBlink).text.toString()
        var resultSmile = findViewById<TextView>(R.id.resultSmile).text.toString()
        var resultMouth = findViewById<TextView>(R.id.resultMouth).text.toString()

        if (resultShake != "COMPLETE"){
            testShake(bitmap)
            Log.e("indicator", indicator.toString())
            Log.e("result", resultShake)
            if (resultShake == "\"left\""){
                if(indicator.equals(0)){
                    indicator += 1
                    lastResult = resultShake
                }
                if (indicator.equals(1) && lastResult == "\"right\""){
                    findViewById<TextView>(R.id.resultShake).text = "COMPLETE"
                    indicator = 0
                }
            }

            if (resultShake == "\"right\""){
                if(indicator.equals(0)){
                    indicator += 1
                    lastResult = resultShake
                }
                if (indicator.equals(1) && lastResult == "\"left\""){
                    findViewById<TextView>(R.id.resultShake).text = "COMPLETE"
                    indicator = 0
                }
            }

        }else if (resultNod != "COMPLETE" && resultShake == "COMPLETE"){
            testNod(bitmap)
            Log.e("indicator", indicator.toString())
            Log.e("result", lastResult)
            if (resultNod == "\"front\""){
                if(indicator.equals(0)){
                    indicator += 1
                    lastResult = resultNod
                }
            }

            if (resultNod == "\"down\"") {
                if (indicator.equals(1) && lastResult == "\"front\"") {
                    findViewById<TextView>(R.id.resultNod).text = "COMPLETE"
                    indicator = 0
                }
            }

        }else if (resultBlink != "COMPLETE" && resultNod == "COMPLETE"){
            testBlink(bitmap)
            if (resultBlink == "\"1\"") {
                findViewById<TextView>(R.id.resultBlink).text = "COMPLETE"
                indicator = 0
            }

        }else if (resultSmile != "COMPLETE" && resultBlink == "COMPLETE"){

            testSmile(bitmap)
            if (resultSmile == "\"1\"") {
                findViewById<TextView>(R.id.resultSmile).text = "COMPLETE"
                indicator = 0
            }

        }else if (resultMouth != "COMPLETE" && resultSmile == "COMPLETE"){
            testMouth(bitmap)
            if (resultMouth == "\"1\"") {
                findViewById<TextView>(R.id.resultMouth).text = "COMPLETE"
                indicator = 0
            }
        }
        if(resultShake== "COMPLETE" && resultNod== "COMPLETE" && resultBlink== "COMPLETE"
                && resultSmile== "COMPLETE" && resultMouth== "COMPLETE"){
            //faceDetection
            faceRecognition(bitmap)
        }

    }

    private fun faceRecognition(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/recognition"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultIdentity).text != response.toString()) {
                    
                    findViewById<TextView>(R.id.resultIdentity).text = response.toString()
                }
            },
            Response.ErrorListener { error ->
                if ((findViewById<TextView>(R.id.resultIdentity).text == "Pending") ||
                    findViewById<TextView>(R.id.resultIdentity).text == "Not Working") {
                    findViewById<TextView>(R.id.resultIdentity).text = "Not working"
                }
            }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun testMouth(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/mouth"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultMouth).text != "COMPLETE") {
                    
                    findViewById<TextView>(R.id.resultMouth).text = response.toString()
                }
            },
            Response.ErrorListener { error -> findViewById<TextView>(R.id.resultMouth).text = "Not working" }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun testSmile(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/smile"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultSmile).text != "COMPLETE") {
                    
                    findViewById<TextView>(R.id.resultSmile).text = response.toString()
                }
            },
            Response.ErrorListener { error -> findViewById<TextView>(R.id.resultSmile).text = "Not working" }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun testBlink(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/blink"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultBlink).text != "COMPLETE") {
                    
                    findViewById<TextView>(R.id.resultBlink).text = response.toString()
                }
            },
            Response.ErrorListener { error -> findViewById<TextView>(R.id.resultBlink).text = "Not working" }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun testNod(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/direction"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultNod).text != "COMPLETE") {
                    
                    findViewById<TextView>(R.id.resultNod).text = response.toString()
                }
            },
            Response.ErrorListener { error -> findViewById<TextView>(R.id.resultNod).text = "Not working" }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }

    private fun testShake(bitmap: Bitmap) {

        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        val base64String: String = Base64.encodeToString(byteArray, Base64.DEFAULT)

        val url = BASE_URL + "/predict/direction"

        // Request a string response from the provided URL.
        val stringRequest = object:StringRequest(Method.POST, url,
            Response.Listener<String> { response ->
                if (findViewById<TextView>(R.id.resultShake).text != "COMPLETE") {
                    
                    findViewById<TextView>(R.id.resultShake).text = response.toString()
                }
            },
            Response.ErrorListener { error -> findViewById<TextView>(R.id.resultShake).text = "Not working" }){

            @Throws(AuthFailureError::class)
            override fun getParams(): Map<String, String> {
                // Add parameters to the request
                val params: MutableMap<String, String> = HashMap()
                params["image"] = base64String
                return params
            }
        }

        // creating a new variable for our request queue
        val queue = Volley.newRequestQueue(this)
        // Add the request to the RequestQueue.
        queue.add(stringRequest)
    }


    @SuppressLint("MissingPermission")
    private fun open_camera() {
        cameraManager.openCamera(cameraManager.cameraIdList[1], object:CameraDevice.StateCallback(){
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(listOf(surface), object: CameraCaptureSession.StateCallback(){
                    override fun onConfigured(p0: CameraCaptureSession) {
                        p0.setRepeatingRequest(captureRequest.build(),null,null)
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {

                    }
                }, handler)

            }

            override fun onDisconnected(p0: CameraDevice) {

            }

            override fun onError(p0: CameraDevice, p1: Int) {
            }
        }, handler)
    }



    fun getPermission(){
        if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0]!=PackageManager.PERMISSION_GRANTED){
            getPermission()
        }
    }

}