package com.ibot.cyranosystems.camerasampleapp

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager.FEATURE_CAMERA_FLASH
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.hardware.camera2.CameraDevice.TEMPLATE_RECORD
import android.media.MediaRecorder
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.example.android.camera2video.*
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_camera2_video.*
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class Camera2VideoFragment : Fragment(), View.OnClickListener,
    ActivityCompat.OnRequestPermissionsResultCallback {

    private val FRAGMENT_DIALOG = "dialog"
    private val TAG = "Camera2VideoFragment"
    private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
    private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }
    private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }
    private var isFlashEnable = false

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    }

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private lateinit var textureView: AutoFitTextureView

    /**
     * Button to record video
     */
    private lateinit var videoButton: ImageView

    /**
     * A reference to the opened [android.hardware.camera2.CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * A reference to the current [android.hardware.camera2.CameraCaptureSession] for
     * preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private lateinit var previewSize: Size

    /**
     * The [android.util.Size] of video recording.
     */
    private lateinit var videoSize: Size

    /**
     * Whether the app is recording video now
     */
    private var isRecordingVideo = false

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /**
     * Orientation of the camera sensor
     */
    private var sensorOrientation = 0
    private var manager: CameraManager? = null

    private lateinit var flash: ImageView
    private lateinit var switchCamera: ImageView
    private lateinit var timerRecording: ImageView
    /** 0 forback camera
     * 1 for front camera
     * Initlity default camera is front camera */
    private val CAMERA_FRONT = "1"
    private val CAMERA_BACK = "0"
    private var cameraId: String = "0"

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@Camera2VideoFragment.cameraDevice = cameraDevice
            startPreview()
            Log.i("TextureView Width:--", textureView.width.toString())
            Log.i("TextureView Height:--", textureView.height.toString())
            configureTransform(textureView.width, textureView.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2VideoFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@Camera2VideoFragment.cameraDevice = null
            activity?.finish()
        }

    }

    /**
     * Output file for video
     */
    private var nextVideoAbsolutePath: String? = null

    private var mediaRecorder: MediaRecorder? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera2_video, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture)
//        videoButton = view.findViewById<Button>(R.id.imgRecordingVideo).also {
//            it.setOnClickListener(this)
//        }
        txtViewCounter.setOnClickListener(this)
        videoButton = view.findViewById(R.id.imgRecordingVideo)
        timerRecording = view.findViewById(R.id.timer)
        imgRecordingVideo.setOnClickListener(this)
        switchCamera = view.findViewById(R.id.switch_camera)
        flash = view.findViewById(R.id.flash)
        flash.setOnClickListener(this)
        switchCamera.setOnClickListener(this)
        timer.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.imgRecordingVideo -> if (isRecordingVideo) stopRecordingVideo() else startRecordingVideo()
            R.id.info -> {
                if (activity != null) {
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.intro_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            R.id.flash -> handleFlash()
            R.id.switch_camera -> switchCamera()
            R.id.timer -> {
                val timer = MyCounter(3000, 1000)
                timer.start()
            }
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }

    /**
     * Requests permissions needed for recording video.
     */
    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasPermissionsGranted(permissions: Array<String>) =
        permissions.none {
            checkSelfPermission((activity as FragmentActivity), it) != PERMISSION_GRANTED
        }

    /**
     * Tries to open a [CameraDevice]. The result is listened by [stateCallback].
     *
     * Lint suppression - permission is checked in [hasPermissionsGranted]
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions()
            return
        }

        val cameraActivity = activity
        if (cameraActivity == null || cameraActivity.isFinishing) return

        manager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
//           //previewRequestBuilder.set(CameraMetadata.FLASH_MODE_TORCH, CameraMetadata.FLASH_MODE_OFF)
//            previewRequestBuilder= cameraDevice?.createCaptureRequest(TEMPLATE_PREVIEW) ?:
//
////            CaptureRequest request = builder.build();
////            cameraCaptureSession.capture(request, null, null);
            // cameraId = manager!!.cameraIdList[0]
            //  manager!!.setTorchMode(cameraId,false)
            // Choose the sizes for camera preview and video recording
            val characteristics = manager!!.getCameraCharacteristics(cameraId)
            val map = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
            sensorOrientation = characteristics.get(SENSOR_ORIENTATION)
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, videoSize
            )
            Log.i("Camera ID", manager!!.cameraIdList[0])
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
            configureTransform(width, height)
            mediaRecorder = MediaRecorder()
            manager!!.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            cameraActivity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    /**
     * Close the [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Update the camera preview. [startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val cameraActivity = activity ?: return

        if (nextVideoAbsolutePath.isNullOrEmpty()) {
            nextVideoAbsolutePath = getVideoFilePath(cameraActivity)
        }

        val rotation = cameraActivity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    private fun getVideoFilePath(context: Context?): String {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        captureSession = cameraCaptureSession
                        updatePreview()
                        activity?.runOnUiThread {
                            videoButton.setImageResource(R.drawable.ic_stop_recording)
                            isRecordingVideo = true
                            mediaRecorder?.start()
                            Snackbar.make(textureView,"Recording Start", Snackbar.LENGTH_LONG).show()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun stopRecordingVideo() {
        isRecordingVideo = false
        videoButton.setImageResource(R.drawable.ic_start_recording)
        mediaRecorder?.apply {
            Snackbar.make(textureView,"Strop Recording", Snackbar.LENGTH_LONG).show()
            stop()
            reset()
        }

        if (activity != null) showToast("Video saved: $nextVideoAbsolutePath")
        nextVideoAbsolutePath = null
        startPreview()
    }

    private fun showToast(message: String) = Toast.makeText(activity, message, LENGTH_SHORT).show()

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private fun chooseVideoSize(choices: Array<Size>): Size {
        for (size in choices) {
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if ((size.width / 16) == (size.height / 9) && size.height <= 4480) {
                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
                    Log.i("Landscape chooseVideoSize Width:--", size.width.toString())
                    Log.i("Landscape chooseVideoSize Height:--", size.height.toString())
                    Log.i("Landscape chooseVideoSize Height/9:--", (size.height / 9).toString())
                    Log.i("Landscape chooseVideoSize Width/16:--", (size.width / 16).toString())
                    return size
                }
            } else {
                Log.i("Portrait chooseVideoSize Width:--", size.width.toString())
                Log.i("Portrait chooseVideoSize Height:--", size.height.toString())
                if ((size.width / 16) == (size.height / 9) && (size.height <= 3840)) {
                    //if((size.getWidth()/16) == (size.getHeight()/9) && size.getWidth() <=4480 ) {
                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
                    return size
                }
//                else if ((size.height / 18) == (size.width / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
//                    //if((size.getWidth()/16) == (size.getHeight()/9) && size.getWidth() <=4480 ) {
//                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
//                    return size
//                }
            }
        }
        return choices[choices.size - 1]
    }
//    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
//        for (size in choices) {
//            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                (size.width / 16) == (size.height / 9) && size.width<= 3840
//            } else {
//                if ((size.width / 16) == (size.height / 9) && (size.width <= 3840)) {
//                    //if((size.getWidth()/16) == (size.getHeight()/9) && size.getWidth() <=4480 ) {
//                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
//                    return size;
//                } else if ((size.height / 18) == (size.width / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
//                    //if((size.getWidth()/16) == (size.getHeight()/9) && size.getWidth() <=4480 ) {
//                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
//                    return size
//                }
//            }
//    }
//        it.width == it.height * 4 / 3 && it.width <= 1080
//    } ?: choices[choices.size - 1]
    //Samsung-S6-choices[0]
    //Samsung-S7-edge-choices[6]
    //OnePlus-5T-choices[15]
    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int,
        aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }
        Log.i("chooseOptimalSize width:--", aspectRatio.width.toString())
        Log.i("chooseOptimalSize Height:--", aspectRatio.height.toString())
//        Log.i("Landscape chooseVideoSize Height/9:--",(size.height /9).toString())
//        Log.i("Landscape chooseVideoSize Width/16:--",(size.width /16).toString())
//        var loopCounter = 0
//        for (size in choices) {
//            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                if ((size.width / 16) == (size.height / 9) && size.width <= 7680) {
//                    //AppLog.e(TAG1, "chooseVideoSize:" + size);
//                    return size;
//                }
//            }else {
//                if ((size.width / 16) == (size.height / 9) && ((size.width <= 1280) || (size.height <= 1920))) {
//                    //AppLog.e(TAG1, "chooseOptimalSize:" + size + "-16:9"+"--LoopPosition---==>"+loopCounter);
//                    return size
//                } else if ((size.width / 18) == (size.height / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
//                    //AppLog.e(TAG1, "chooseOptimalSize:" + size + "-18:9"+"--LoopPosition---==>"+loopCounter);
//                    return size
//                } else if ((size.width / 18.5.roundToInt()) == (size.height / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
//                    //AppLog.e(TAG1, "chooseOptimalSize:" + size + "-18.5:9"+"--LoopPosition---==>"+loopCounter);
//                    return size
//                } else if((width/19) == (height/9) && ((width <=2208)||(height<=3216))) {
//                    /*if((width/19) == (height/9) && ((width <=3840)||(height<=2160))) {*/
//                    /*if((size.getWidth()/19) == (size.getHeight()/9) && ((size.getWidth() <=3840)||(size.getHeight()<=2160))) {*/
//                    //AppLog.e(TAG1, "chooseOptimalSize:" + size + "-19:9"+"--LoopPosition---==>"+loopCounter);
//                    return size
//                } else if ((size.width / 19.5.roundToInt()) == (size.height / 9) && ((size.width <= 3840) || (size.height <= 2160))) {
//                    //AppLog.e(TAG1, "chooseOptimalSize:" + size + "-19.5:9"+"--LoopPosition---==>"+loopCounter);
//                    return size;
//                } else {
//                   // AppLog.e(TAG1, "chooseOptimalSize" + " not proper aspect resolution");
//                }
//            }
//            loopCounter++;
//        }
//        // Pick the smallest of those, assuming we found any
//        if (bigEnough.size() > 0) {
//            return Collections.min(bigEnough, CompareSizesByArea());
//        } else {
//            return choices[0];
//        }
        return if (bigEnough.isNotEmpty()) {
            Log.i("chooseOptimalSize bigEnough:--", bigEnough.toString())
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun switchCamera() {
        if (cameraId.equals(CAMERA_FRONT)) {
            cameraId = CAMERA_BACK
            switch_camera.setImageResource(R.drawable.ic_camera_front_white_24dp)
            closeCamera()
            if (textureView.isAvailable) {
                openCamera(textureView.width, textureView.height)
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        } else if (cameraId.equals(CAMERA_BACK)) {
            cameraId = CAMERA_FRONT
            switch_camera.setImageResource(R.drawable.ic_switch_camera_white_24dp)
            closeCamera()
            if (textureView.isAvailable) {
                openCamera(textureView.width, textureView.height)
            } else {
                textureView.surfaceTextureListener = surfaceTextureListener
            }
        }

    }

    private fun handleFlash() {
        if (!isFlashAvailable()) {
            AlertDialog.Builder(activity).setTitle("Error!")
                .setMessage("Your device doesn't support flash light!")
                .setPositiveButton(android.R.string.ok, { dialog, which -> dialog.dismiss() })
                .show()
        } else {
            setFlash(isFlashEnable)
        }
    }

    fun setFlash(isFlash: Boolean) {
        if (textureView.context.packageManager.hasSystemFeature(FEATURE_CAMERA_FLASH)) {
            when (cameraId) {
                CAMERA_FRONT -> {
                    Log.e("Camera2", "Front Camera Flash isn't supported yet.")
                    AlertDialog.Builder(activity).setTitle("Camera")
                        .setMessage("Front Camera Flash isn't supported yet.")
                        .setPositiveButton(
                            android.R.string.ok,
                            { dialog, which -> dialog.dismiss() })
                        .show()
                }
                CAMERA_BACK -> enableFlash(isFlash)
            }
        } else {
            // manager!!.setTorchMode(cameraId,true)
        }
    }

    private fun enableFlash(isFlash: Boolean) {
        if (!isFlash) {
            isFlashEnable = true
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            flash.setImageResource(R.drawable.ic_flash_on_white_24dp)
        } else {
            isFlashEnable = false
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            previewRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            flash.setImageResource(R.drawable.ic_flash_off_white_24dp)
        }
        captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
    }

    private fun isFlashAvailable(): Boolean {
        var isFlashAvailable = requireContext().getPackageManager().hasSystemFeature(
            FEATURE_CAMERA_FLASH
        )
        return isFlashAvailable
    }

    companion object {
        fun newInstance(): Camera2VideoFragment = Camera2VideoFragment()
    }
//private fun setCountDownAnimation(txtViewCounter:TextView){
//    val fadeOut = AlphaAnimation(1f, 0f)
//    fadeOut.interpolator = AccelerateInterpolator()
//    fadeOut.duration = 1000
//
//    val animation = AnimationSet(false)
//    animation.addAnimation(fadeOut)
//    view?.startAnimation(animation)
//}
    inner class MyCounter(millisInFuture: Long, countDownInterval: Long) :
        CountDownTimer(millisInFuture, countDownInterval) {

        override fun onFinish() {
            cancel()
            startRecordingVideo()
            txtViewCounter.setText("")
        }

        override fun onTick(millisUntilFinished: Long) {
            txtViewCounter.textSize = 150f
            txtViewCounter.animate().alpha(1.0f).setDuration(1000).setInterpolator(AccelerateInterpolator()).start()

//            val alphaAnimation: Animation=ScaleAnimation(activity,1.0f,0.0f,1.0f)
//            alphaAnimation : Animation = ScaleAnimation()
//            // Use a set of animations
//            Animation scaleAnimation = new ScaleAnimation(1.0f, 0.0f, 1.0f,
//                0.0f, Animation.RELATIVE_TO_SELF, 0.5f,
//                Animation.RELATIVE_TO_SELF, 0.5f);
//            Animation alphaAnimation = new AlphaAnimation(1.0f, 0.0f);
//            AnimationSet animationSet = new AnimationSet(false);
//            animationSet.addAnimation(scaleAnimation);
//            animationSet.addAnimation(alphaAnimation);
//            countDownAnimation.setAnimation(animationSet);

            txtViewCounter.text = (millisUntilFinished / 1000).toString() + ""
            println("Timer  : " + millisUntilFinished / 1000)
        }
    }
}