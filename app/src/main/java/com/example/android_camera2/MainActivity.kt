package com.example.android_camera2

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Collections
import java.util.Date


class MainActivity : AppCompatActivity() {
    private lateinit var cameraCharacteristics:CameraCharacteristics
    private var mCaptureState = STATE_PREVIEW
    private var mTextureView: TextureView? = null
    private val mSurfaceTextureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            setupCamera(width, height)
            connectCamera()
            transformImage(width,height)
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }
    private var mCameraDevice: CameraDevice? = null
    private val mCameraDeviceStateCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                mCameraDevice = camera
                mMediaRecorder = MediaRecorder()
                if (mIsRecording) {
                    try {
                        createVideoFileName()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    startRecord()
                    mMediaRecorder!!.start()
                    runOnUiThread {
                        mChronometer!!.base = SystemClock.elapsedRealtime()
                        mChronometer!!.visibility = View.VISIBLE
                        mChronometer!!.start()
                    }
                } else {
                    startPreview()
                }
                // Toast.makeText(getApplicationContext(),
                //         "Camera connection made!", Toast.LENGTH_SHORT).show();
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                mCameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                mCameraDevice = null
            }
        }
    private var mBackgroundHandlerThread: HandlerThread? = null
    private var mBackgroundHandler: Handler? = null
    private var mCameraId: String? = null
    private var mPreviewSize: Size? = null
    private var mVideoSize: Size? = null
    private var mImageSize: Size? = null
    private var mImageReader: ImageReader? = null
    private val mOnImageAvailableListener =
        OnImageAvailableListener { reader -> mBackgroundHandler!!.post(ImageSaver(reader.acquireLatestImage())) }

    private inner class ImageSaver(private val mImage: Image) : Runnable {
        override fun run() {
            val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer[bytes]
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(mImageFileName)
                fileOutputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                mImage.close()
                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaStoreUpdateIntent.data = Uri.fromFile(File(mImageFileName))
                sendBroadcast(mediaStoreUpdateIntent)
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private var mMediaRecorder: MediaRecorder? = null
    private var mChronometer: Chronometer? = null
    private var mTotalRotation = 0
    private var mPreviewCaptureSession: CameraCaptureSession? = null
    private val mPreviewCaptureCallback: CaptureCallback = object : CaptureCallback() {
        private fun process(captureResult: CaptureResult) {
            when (mCaptureState) {
                STATE_PREVIEW -> {}
                STATE_WAIT_LOCK -> {
                    mCaptureState = STATE_PREVIEW
                    val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) {
                        Toast.makeText(applicationContext, "AF Locked!", Toast.LENGTH_SHORT).show()
                        startStillCaptureRequest()
                    }
                }
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }
    private var mRecordCaptureSession: CameraCaptureSession? = null
    private val mRecordCaptureCallback: CaptureCallback = object : CaptureCallback() {
        private fun process(captureResult: CaptureResult) {
            when (mCaptureState) {
                STATE_PREVIEW -> {}
                STATE_WAIT_LOCK -> {
                    mCaptureState = STATE_PREVIEW
                    val afState = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) {
                        Toast.makeText(applicationContext, "AF Locked!", Toast.LENGTH_SHORT).show()
                        startStillCaptureRequest()
                    }
                }
            }
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }
    }
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mRecordImageButton: ImageButton? = null
    private var mStillImageButton: ImageButton? = null
    private var mIsRecording = false
    private var mIsTimelapse = false
    private var mVideoFolder: File? = null
    private var mVideoFileName: String? = null
    private var mImageFolder: File? = null
    private var mImageFileName: String? = null

    private class CompareSizeByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum((lhs.width * lhs.height).toLong() - (rhs.width * rhs.height).toLong())
        }
    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//        createVideoFolder()
//        createImageFolder()
//        mChronometer = findViewById<View>(R.id.chronometer) as Chronometer
//        mTextureView = findViewById<View>(R.id.textureView) as TextureView
//        mStillImageButton = findViewById<View>(R.id.cameraImageButton2) as ImageButton
//        mStillImageButton!!.setOnClickListener {
//            if (!(mIsTimelapse || mIsRecording)) {
//                checkWriteStoragePermission()
//            }
//            lockFocus()
//        }
//        mRecordImageButton = findViewById<View>(R.id.videoOnlineImageButton) as ImageButton
//        mRecordImageButton!!.setOnClickListener {
//            if (mIsRecording || mIsTimelapse) {
//                mChronometer!!.stop()
//                mChronometer!!.visibility = View.INVISIBLE
//                mIsRecording = false
//                mIsTimelapse = false
//                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_online)
//
//                // Starting the preview prior to stopping recording which should hopefully
//                // resolve issues being seen in Samsung devices.
//                startPreview()
//                mMediaRecorder!!.stop()
//                mMediaRecorder!!.reset()
//                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
//                mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
//                sendBroadcast(mediaStoreUpdateIntent)
//            } else {
//                mIsRecording = true
//                mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
//                checkWriteStoragePermission()
//            }
//        }
//        mRecordImageButton!!.setOnLongClickListener {
//            mIsTimelapse = true
//            mRecordImageButton!!.setImageResource(R.mipmap.btn_timelapse)
//            checkWriteStoragePermission()
//            true
//        }
//    }
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    createImageFolder()

    mChronometer = findViewById<View>(R.id.chronometer) as Chronometer
    mTextureView = findViewById<View>(R.id.textureView) as TextureView
    mStillImageButton = findViewById<View>(R.id.cameraImageButton2) as ImageButton
    mStillImageButton!!.setOnClickListener {
        lockFocus()
    }
}

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (mTextureView!!.isAvailable) {
            setupCamera(mTextureView!!.width, mTextureView!!.height)
            connectCamera()
            transformImage(mTextureView!!.width,mTextureView!!.height)
        } else {
            mTextureView!!.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    applicationContext,
                    "Application will not run without camera services", Toast.LENGTH_SHORT
                ).show()
            }
            if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    applicationContext,
                    "Application will not have audio on record", Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (mIsRecording || mIsTimelapse) {
                    mIsRecording = true
                    mRecordImageButton!!.setImageResource(R.mipmap.btn_video_busy)
                }
                Toast.makeText(
                    this,
                    "Permission successfully granted!", Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "App needs to save video to run", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocas: Boolean) {
        super.onWindowFocusChanged(hasFocas)
        val decorView = window.decorView
        if (hasFocas) {
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
                ) {
                    continue
                }
                val map =
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation = mTotalRotation == 90 || mTotalRotation == 270
                var rotatedWidth = width
                var rotatedHeight = height
                if (swapRotation) {
                    rotatedWidth = height
                    rotatedHeight = width
                }
                mPreviewSize = chooseOptimalSize(
                    map!!.getOutputSizes(
                        SurfaceTexture::class.java
                    ), rotatedWidth, rotatedHeight,4,3
                )
//                mVideoSize = chooseOptimalSize(
//                    map.getOutputSizes(
//                        MediaRecorder::class.java
//                    ), rotatedWidth, rotatedHeight
//                )
                mImageSize = chooseOptimalSize(
                    map.getOutputSizes(ImageFormat.JPEG),
                    rotatedWidth,
                    rotatedHeight,4,3
                )
                mImageReader = ImageReader.newInstance(
                    mImageSize!!.width,
                    mImageSize!!.height,
                    ImageFormat.JPEG,
                    1
                )
                mImageReader!!.setOnImageAvailableListener(
                    mOnImageAvailableListener,
                    mBackgroundHandler
                )
                mCameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun transformImage(width: Int, height: Int) {
        if (mPreviewSize == null || mTextureView == null) {
            return
        }
        val matrix = Matrix()
        val rotation = windowManager.defaultDisplay.rotation
        val textureRectF = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val previewRectF =
            RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
        val centerX = textureRectF.centerX()
        val centerY = textureRectF.centerY()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            previewRectF.offset(
                centerX - previewRectF.centerX(),
                centerY - previewRectF.centerY()
            )
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                width.toFloat() / mPreviewSize!!.width,
                height.toFloat() / mPreviewSize!!.height
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        mTextureView!!.setTransform(matrix)
    }



    private fun connectCamera() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    cameraManager.openCamera(
                        mCameraId!!,
                        mCameraDeviceStateCallback,
                        mBackgroundHandler
                    )
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(
                            this,
                            "Video app required access to camera", Toast.LENGTH_SHORT
                        ).show()
                    }
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                        ), REQUEST_CAMERA_PERMISSION_RESULT
                    )
                }
            } else {
                cameraManager.openCamera(
                    mCameraId!!,
                    mCameraDeviceStateCallback,
                    mBackgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startRecord() {
        try {
            if (mIsRecording) {
                setupMediaRecorder()
            } else if (mIsTimelapse) {
                setupTimelapse()
            }
            val surfaceTexture = mTextureView!!.surfaceTexture
            surfaceTexture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            val previewSurface = Surface(surfaceTexture)
            val recordSurface = mMediaRecorder!!.surface
            mCaptureRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mCaptureRequestBuilder!!.addTarget(previewSurface)
            mCaptureRequestBuilder!!.addTarget(recordSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, recordSurface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        mRecordCaptureSession = session
                        try {
                            mRecordCaptureSession!!.setRepeatingRequest(
                                mCaptureRequestBuilder!!.build(), null, null
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigureFailed: startRecord")
                    }
                }, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPreview() {
        val surfaceTexture = mTextureView!!.surfaceTexture
        surfaceTexture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
        val previewSurface = Surface(surfaceTexture)
        try {
            mCaptureRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder!!.addTarget(previewSurface)
            mCameraDevice!!.createCaptureSession(
                Arrays.asList(previewSurface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigured: startPreview")
                        mPreviewCaptureSession = session
                        try {
                            mPreviewCaptureSession!!.setRepeatingRequest(
                                mCaptureRequestBuilder!!.build(),
                                null, mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigureFailed: startPreview")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

//    private fun startStillCaptureRequest() {
//        try {
//            mCaptureRequestBuilder = if (mIsRecording) {
//                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
//            } else {
//                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
//            }
//            mCaptureRequestBuilder!!.addTarget(mImageReader!!.surface)
//            mCaptureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation)
//            val stillCaptureCallback: CaptureCallback = object : CaptureCallback() {
//                override fun onCaptureStarted(
//                    session: CameraCaptureSession,
//                    request: CaptureRequest,
//                    timestamp: Long,
//                    frameNumber: Long
//                ) {
//                    super.onCaptureStarted(session, request, timestamp, frameNumber)
//                    try {
//                        createImageFileName()
//                        Log.d("Camera Path:",createImageFileName().toString())
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                    }
//                }
//            }
//            if (mIsRecording) {
//                mRecordCaptureSession!!.capture(
//                    mCaptureRequestBuilder!!.build(),
//                    stillCaptureCallback,
//                    null
//                )
//            } else {
//                mPreviewCaptureSession!!.capture(
//                    mCaptureRequestBuilder!!.build(),
//                    stillCaptureCallback,
//                    null
//                )
//            }
//        } catch (e: CameraAccessException) {
//            e.printStackTrace()
//        }
//    }
private fun startStillCaptureRequest() {
    try {
        mCaptureRequestBuilder = if (mIsRecording) {
            mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
        } else {
            mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        }

        // Calculate the total rotation taking into account device and sensor orientation
        val deviceOrientation = windowManager.defaultDisplay.rotation
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val totalRotation = sensorOrientation?.let { sensorToDeviceRotation(it, deviceOrientation) }

        mCaptureRequestBuilder!!.addTarget(mImageReader!!.surface)
        mCaptureRequestBuilder!!.set(CaptureRequest.JPEG_ORIENTATION, totalRotation)

        val stillCaptureCallback: CaptureCallback = object : CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                try {
                    createImageFileName()
                    Log.d("Camera Path:", createImageFileName().toString())
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        if (mIsRecording) {
            mRecordCaptureSession!!.capture(
                mCaptureRequestBuilder!!.build(),
                stillCaptureCallback,
                null
            )
        } else {
            mPreviewCaptureSession!!.capture(
                mCaptureRequestBuilder!!.build(),
                stillCaptureCallback,
                null
            )
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }
}

    private fun sensorToDeviceRotation(sensorOrientation: Int, deviceOrientation: Int): Int {
        // Calculate the total rotation needed to transform sensor data to device orientation
        return (sensorOrientation + deviceOrientation + 360) % 360
    }
    private fun closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice!!.close()
            mCameraDevice = null
        }
        if (mMediaRecorder != null) {
            mMediaRecorder!!.release()
            mMediaRecorder = null
        }
    }

    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("Camera2VideoImage")
        mBackgroundHandlerThread!!.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundHandlerThread!!.quitSafely()
        try {
            mBackgroundHandlerThread!!.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun createVideoFolder() {
        val movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(movieFile, "camera2VideoImage")
        if (!mVideoFolder!!.exists()) {
            mVideoFolder!!.mkdirs()
        }
    }

    @Throws(IOException::class)
    private fun createVideoFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_" + timestamp + "_"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile
    }

    private fun createImageFolder() {
        val imageFile =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "camera2VideoImage")
        if (!mImageFolder!!.exists()) {
            mImageFolder!!.mkdirs()
        }
    }

    @Throws(IOException::class)
    private fun createImageFileName(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_" + timestamp + "_"
        val imageFile = File.createTempFile(prepend, ".jpg", mImageFolder)
        mImageFileName = imageFile.absolutePath
        return imageFile
    }

//    private fun checkWriteStoragePermission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
//                == PackageManager.PERMISSION_GRANTED
//            ) {
//                try {
//                    createVideoFileName()
//                } catch (e: IOException) {
//                    e.printStackTrace()
//                }
//                if (mIsTimelapse || mIsRecording) {
//                    startRecord()
//                    mMediaRecorder!!.start()
//                    mChronometer!!.base = SystemClock.elapsedRealtime()
//                    mChronometer!!.visibility = View.VISIBLE
//                    mChronometer!!.start()
//                }
//            } else {
//                if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
//                    Toast.makeText(this, "app needs to be able to save videos", Toast.LENGTH_SHORT)
//                        .show()
//                }
//                requestPermissions(
//                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
//                    REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT
//                )
//            }
//        } else {
//            try {
//                createVideoFileName()
//            } catch (e: IOException) {
//                e.printStackTrace()
//            }
//            if (mIsRecording || mIsTimelapse) {
//                startRecord()
//                mMediaRecorder!!.start()
//                mChronometer!!.base = SystemClock.elapsedRealtime()
//                mChronometer!!.visibility = View.VISIBLE
//                mChronometer!!.start()
//            }
//        }
//    }
private fun checkWriteStoragePermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                createImageFileName()
                startStillCaptureRequest()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "App needs to be able to save images", Toast.LENGTH_SHORT)
                    .show()
            }
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT
            )
        }
    } else {
        try {
            createImageFileName()
            startStillCaptureRequest()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}

    @Throws(IOException::class)
    private fun setupMediaRecorder() {
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder!!.setOutputFile(mVideoFileName)
        mMediaRecorder!!.setVideoEncodingBitRate(1000000)
        mMediaRecorder!!.setVideoFrameRate(30)
        mMediaRecorder!!.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mMediaRecorder!!.setOrientationHint(mTotalRotation)
        mMediaRecorder!!.prepare()
    }

    @Throws(IOException::class)
    private fun setupTimelapse() {
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_TIME_LAPSE_HIGH))
        mMediaRecorder!!.setOutputFile(mVideoFileName)
        mMediaRecorder!!.setCaptureRate(2.0)
        mMediaRecorder!!.setOrientationHint(mTotalRotation)
        mMediaRecorder!!.prepare()
    }

    private fun lockFocus() {
        mCaptureState = STATE_WAIT_LOCK
        mCaptureRequestBuilder!!.set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_START
        )
        try {
            if (mIsRecording) {
                mRecordCaptureSession!!.capture(
                    mCaptureRequestBuilder!!.build(),
                    mRecordCaptureCallback,
                    mBackgroundHandler
                )
            } else {
                mPreviewCaptureSession!!.capture(
                    mCaptureRequestBuilder!!.build(),
                    mPreviewCaptureCallback,
                    mBackgroundHandler
                )
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "Camera2VideoImageActivi"
        private const val REQUEST_CAMERA_PERMISSION_RESULT = 0
        private const val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1
        private const val STATE_PREVIEW = 0
        private const val STATE_WAIT_LOCK = 1
        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 0)
            ORIENTATIONS.append(Surface.ROTATION_90, 90)
            ORIENTATIONS.append(Surface.ROTATION_180, 180)
            ORIENTATIONS.append(Surface.ROTATION_270, 270)
        }

        private fun sensorToDeviceRotation(
            cameraCharacteristics: CameraCharacteristics,
            deviceOrientation: Int
        ): Int {
            var deviceOrientation = deviceOrientation
            val sensorOrienatation =
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
            deviceOrientation = ORIENTATIONS[deviceOrientation]
            return (sensorOrienatation + deviceOrientation + 360) % 360
        }

//        private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
//            val bigEnough: MutableList<Size> = ArrayList()
//            for (option in choices) {
//                if (option.height == option.width * height / width && option.width >= width && option.height >= height) {
//                    bigEnough.add(option)
//                }
//            }
//            return if (bigEnough.size > 0) {
//                Collections.min(bigEnough, CompareSizeByArea())
//            } else {
//                choices[0]
//            }
//        }
private fun chooseOptimalSize(
    choices: Array<Size>,
    width: Int,
    height: Int,
    aspectRatioWidth: Int,
    aspectRatioHeight: Int
): Size {
    val desiredAspectRatio = aspectRatioWidth.toFloat() / aspectRatioHeight.toFloat()
    var optimalSize: Size? = null
    var minAspectRatioDiff = Float.MAX_VALUE

    for (size in choices) {
        val currentAspectRatio = size.width.toFloat() / size.height.toFloat()
        val aspectRatioDiff = Math.abs(currentAspectRatio - desiredAspectRatio)

        // Check if the aspect ratio is closer to the desired one
        if (aspectRatioDiff < minAspectRatioDiff) {
            optimalSize = size
            minAspectRatioDiff = aspectRatioDiff
        }
    }

    return optimalSize ?: choices[0] // Return the default size if no match is found
}

    }
}