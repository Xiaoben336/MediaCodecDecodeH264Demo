package com.example.encodeh264;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
import static android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class Camera2Preview extends TextureView {
    private static final String TAG = "Camera2Preview";
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    protected CameraCaptureSession mCameraCaptureSessions;
    protected CaptureRequest.Builder mPreviewRequestBuilder;
    private ImageReader mImageReader;
    private Context mContext;

    private Size mPreviewSize;

    private static final String CAMERA_FONT = "0";
    private static final String CAMERA_BACK = "1";
    private String mCameraId;
    public Camera2Preview(Context context) {
        this(context, null);
    }


    public Camera2Preview(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }


    public Camera2Preview(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mContext = context;
        setKeepScreenOn(true);
        getDefaultCameraId();
    }

    SurfaceTextureListener textureListener = new SurfaceTextureListener(){

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //开启摄像头
            setupCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    public void onResume(){
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (isAvailable()) {
            setupCamera();
        } else {
            setSurfaceTextureListener(textureListener);
        }
    }


    public void onPause() {
        Log.e(TAG, "onPause");
        if (mAvcEncoder != null) {
            mAvcEncoder.stopThread();
            mAvcEncoder = null;
        }
        closeCamera();
        stopBackgroundThread();
    }

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void closeCamera() {
        closePreviewSession();

        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }

        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void closePreviewSession() {
        if (null != mCameraCaptureSessions) {
            mCameraCaptureSessions.close();
            mCameraCaptureSessions = null;
        }
    }

    /**
     * 开启摄像机线程
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

    }

    /**
     * 开启摄像头
     */
    private void setupCamera() {
        Log.e(TAG,"setupCamera START");
        if (mCameraManager == null) {
            Log.e(TAG,"尚未得到CameraManager");
            return;
        }
        try {
            //获取相机特征对象
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            //获取相机输出流配置
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            //获取预览输出尺寸
            mPreviewSize = getPreferredPreviewSize(map.getOutputSizes(SurfaceTexture.class),getWidth(),getHeight());
            Log.e(TAG, "setupCamera: best preview size width=" + mPreviewSize.getWidth()
                    + ",height=" + mPreviewSize.getHeight());
            transformImage(getWidth(),getHeight());

            if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            setupImageReader();
            mCameraManager.openCamera(mCameraId,stateCallback,null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG,"setupCamera END");
    }


    private Size getPreferredPreviewSize(Size[] mapSizes, int width, int height) {
        Log.e(TAG, "getPreferredPreviewSize: surface width=" + width + ",surface height=" + height);
        List<Size> collectorSizes = new ArrayList<>();
        for (Size option : mapSizes) {
            if (width > height) {
                if (option.getWidth() > width &&
                        option.getHeight() > height) {
                    collectorSizes.add(option);
                }
            } else {
                if (option.getWidth() > height &&
                        option.getHeight() > width) {
                    collectorSizes.add(option);
                }
            }
        }
        if (collectorSizes.size() > 0) {
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        }
        Log.e(TAG, "getPreferredPreviewSize: best width=" +
                mapSizes[0].getWidth() + ",height=" + mapSizes[0].getHeight());
        return mapSizes[0];
    }


    private void transformImage(int width, int height) {
        Matrix matrix = new Matrix();
        int rotation = ((Activity) mContext).getWindowManager().getDefaultDisplay().getRotation();
        RectF textureRectF = new RectF(0, 0, width, height);
        RectF previewRectF = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = textureRectF.centerX();
        float centerY = textureRectF.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            previewRectF.offset(centerX - previewRectF.centerX(),
                    centerY - previewRectF.centerY());
            matrix.setRectToRect(textureRectF, previewRectF, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) width / mPreviewSize.getWidth(),
                    (float) height / mPreviewSize.getHeight());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        setTransform(matrix);
    }

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_RECORD = 1;
    private int mState = STATE_PREVIEW;
    private AvcEncoder mAvcEncoder;
    private int mFrameRate = 30;

    private void setupImageReader() {
        //2代表ImageReader中最多可以获取两帧图像流
        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(),mPreviewSize.getHeight(), ImageFormat.YV12,1);
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Log.e(TAG, "onImageAvailable: "+Thread.currentThread().getName() );
                //这里一定要调用reader.acquireNextImage()和img.close方法否则不会一直回掉了
                Image img = reader.acquireNextImage();
                switch (mState){
                    case STATE_PREVIEW:
                        Log.e(TAG, "mState: STATE_PREVIEW");
                        if (mAvcEncoder != null) {
                            mAvcEncoder.stopThread();
                            mAvcEncoder = null;
                            Toast.makeText(mContext,"停止录制视频成功",Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case STATE_RECORD:
                        Log.e(TAG, "mState: STATE_RECORD");
                        Image.Plane[] planes = img.getPlanes();
                        byte[] dataYUV = null;
                        if (planes.length >= 3) {
                            ByteBuffer bufferY = planes[0].getBuffer();
                            ByteBuffer bufferU = planes[1].getBuffer();
                            ByteBuffer bufferV = planes[2].getBuffer();
                            int lengthY = bufferY.remaining();
                            int lengthU = bufferU.remaining();
                            int lengthV = bufferV.remaining();
                            dataYUV = new byte[lengthY + lengthU + lengthV];
                            bufferY.get(dataYUV, 0, lengthY);
                            bufferU.get(dataYUV, lengthY, lengthU);
                            bufferV.get(dataYUV, lengthY + lengthU, lengthV);
                        }

                        if (mAvcEncoder == null) {
                            mAvcEncoder = new AvcEncoder(mPreviewSize.getWidth(),
                                    mPreviewSize.getHeight(), mFrameRate,
                                    getOutputMediaFile(MEDIA_TYPE_VIDEO), false);
                            mAvcEncoder.startEncoderThread();
                            Toast.makeText(mContext, "开始录制视频成功", Toast.LENGTH_SHORT).show();
                        }
                        mAvcEncoder.putYUVData(dataYUV);
                        break;
                        default:
                            break;
                }
                img.close();
            }
        },mBackgroundHandler);
    }

    /**
     * 获取输出照片视频路径
     *
     * @param mediaType
     * @return
     */
    public File getOutputMediaFile(int mediaType) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = null;
        File storageDir = null;
        if (mediaType == MEDIA_TYPE_IMAGE) {
            fileName = "JPEG_" + timeStamp + "_";
            storageDir = mContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        } else if (mediaType == MEDIA_TYPE_VIDEO) {
            fileName = "MP4_" + timeStamp + "_";
            storageDir = mContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        }

        // Create the storage directory if it does not exist
        if (!storageDir.exists()) {
            if (!storageDir.mkdirs()) {
                Log.d(TAG, "failed to create directory");
                return null;
            }
        }

        File file = null;
        try {
            file = File.createTempFile(
                    fileName,  /* prefix */
                    (mediaType == MEDIA_TYPE_IMAGE) ? ".jpg" : ".h264",         /* suffix */
                    storageDir      /* directory */
            );
            Log.d(TAG, "getOutputMediaFile: absolutePath==" + file.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }


    private void getDefaultCameraId() {
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraList = mCameraManager.getCameraIdList();
            for (int i = 0;i < cameraList.length;i++) {
                String cameraId = cameraList[i];
                if (TextUtils.equals(cameraId,CAMERA_FONT)) {
                    mCameraId = cameraId;
                    break;
                } else if (TextUtils.equals(cameraId,CAMERA_BACK)) {
                    mCameraId = cameraId;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.e(TAG,"onOpened...");
            mCameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };

    /**
     * 创建预览界面
     */
    private void createCameraPreview() {
        try {
            Log.e(TAG,"createCameraPreview");
            //获取当前TextureView的SurfaceTexture
            SurfaceTexture texture = getSurfaceTexture();
            assert texture != null;
            //设置SurfaceTexture默认的缓存区大小，为 上面得到的预览的size大小
            texture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
            Surface surface = new Surface(texture);
            //创建CaptureRequest对象，并且声明类型为TEMPLATE_PREVIEW，可以看出是一个预览类型

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            //CaptureRequest.
            //设置请求的结果返回到到Surface上
            mPreviewRequestBuilder.addTarget(surface);

            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            //创建CaptureSession对象
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    //The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    Log.e(TAG, "onConfigured: ");
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSessions = session;
                    //更新预览
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(mContext, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            },null);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    /**
     * 更新预览
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        Log.e(TAG, "updatePreview: ");
        //设置相机的控制模式为自动，方法具体含义点进去（auto-exposure, auto-white-balance, auto-focus）
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            //设置重复捕获图片信息
            mCameraCaptureSessions.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public boolean toggleVideo() {
        if (mState == STATE_PREVIEW) {
            mState = STATE_RECORD;
            return true;
        } else {
            mState = STATE_PREVIEW;
            return false;
        }
    }

}
