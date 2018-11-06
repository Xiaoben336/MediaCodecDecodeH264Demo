package com.example.encodeh264;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.support.annotation.RequiresApi;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
public class AvcEncoder {
    private static final String TAG = "AvcEncoder";
    private int TIMEOUT_USEC = 12000;
    private int mYuvQueueSize = 10;

    public ArrayBlockingQueue<byte[]> mYuvQueue = new ArrayBlockingQueue<>(mYuvQueueSize);

    private MediaCodec mMediaCodec;
    private int mWidth;
    private int mHeight;
    private int mFrameRate;

    private File mOutFile;
    //true--Camera的预览数据编码
    // false--Camera2的预览数据编码
    private boolean mIsCamera;

    public byte[] mConfigByte;


    public AvcEncoder(int width, int height, int frameRate, File outFile, boolean isCamera){
        mIsCamera = isCamera;
        mWidth = width;
        mHeight = height;
        mFrameRate = frameRate;
        mOutFile = outFile;

        MediaFormat mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE,width * height * 5);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE,30);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);

        try {
            mMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mMediaCodec.configure(mediaFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();
        createFile();
    }

    private BufferedOutputStream outputStream;
    private void createFile() {
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(mOutFile));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void stopEncoder(){
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
    }

    public void putYUVData(byte[] buffer) {
        if (mYuvQueue.size() >= 10) {
            mYuvQueue.poll();
        }
        mYuvQueue.add(buffer);
    }

    public void stopThread(){
        if (!isRunning) return;
        isRunning = false;
         try {
             stopEncoder();
             if (outputStream != null) {
                 outputStream.flush();
                 outputStream.close();
                 outputStream = null;
             }
         } catch (IOException e) {
             e.printStackTrace();
         }

    }

    public boolean isRunning = false;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void startEncoderThread(){
        Thread encoderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                isRunning = true;
                byte[] input = null;
                long pts = 0;
                long generateIndex = 0;

                while (isRunning) {
                    if (mYuvQueue.size() > 0) {
                        input = mYuvQueue.poll();
                        if (mIsCamera) {//Camera  NV21
                            //NV12数据所需空间为如下，所以建立如下缓冲区
                            //y=W*h;u=W*H/4;v=W*H/4,so total add is W*H*3/2 (1 + 1/4 + 1/4 = 3/2)
                            byte[] yuv420sp = new byte[mWidth * mHeight *3 /2];
                            NV21ToNV12(input, yuv420sp, mWidth, mHeight);
                            input = yuv420sp;
                        } else {//Camera 2
                            byte[] yuv420sp = new byte[mWidth * mHeight *3 /2];
                            YV12toNV12(input, yuv420sp, mWidth, mHeight);
                            input = yuv420sp;
                        }
                    }

                    if (input != null) {
                        int inputBufferIndex = mMediaCodec.dequeueInputBuffer(-1);
                        if (inputBufferIndex >= 0) {
                            pts = computePresentationTime(generateIndex);
                            ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
                            inputBuffer.clear();
                            inputBuffer.put(input);
                            mMediaCodec.queueInputBuffer(inputBufferIndex,0,input.length,pts,0);
                            generateIndex += 1;
                        }

                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                        int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo,TIMEOUT_USEC);
                        while (outputBufferIndex >= 0) {
                            ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex);
                            byte[] outData = new byte[bufferInfo.size];
                            outputBuffer.get(outData);
                            if (bufferInfo.flags == 2) {
                                mConfigByte = new byte[bufferInfo.size];
                                mConfigByte = outData;
                            } else if (bufferInfo.flags == 1) {
                                byte[] keyframe = new byte[bufferInfo.size + mConfigByte.length];
                                System.arraycopy(mConfigByte, 0, keyframe, 0, mConfigByte.length);
                                System.arraycopy(outData, 0, keyframe, mConfigByte.length, outData.length);
                                try {
                                    outputStream.write(keyframe, 0, keyframe.length);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            } else {
                                try {
                                    outputStream.write(outData, 0, outData.length);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }

                            mMediaCodec.releaseOutputBuffer(outputBufferIndex,false);
                            outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo,TIMEOUT_USEC);
                        }
                    } else {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
        encoderThread.start();
    }

    private void NV21ToNV12(byte[] nv21, byte[] nv12, int width, int height) {
        if (nv21 == null || nv12 == null) return;
        int framesize = width * height;
        int i = 0, j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for (i = 0; i < framesize; i++) {
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j - 1] = nv21[j + framesize];
        }
        for (j = 0; j < framesize / 2; j += 2) {
            nv12[framesize + j] = nv21[j + framesize - 1];
        }
    }

    private void YV12toNV12(byte[] yv12bytes, byte[] nv12bytes, int width, int height) {

        int nLenY = width * height;
        int nLenU = nLenY / 4;


        System.arraycopy(yv12bytes, 0, nv12bytes, 0, width * height);
        for (int i = 0; i < nLenU; i++) {
            nv12bytes[nLenY + 2 * i] = yv12bytes[nLenY + i];
            nv12bytes[nLenY + 2 * i + 1] = yv12bytes[nLenY + nLenU + i];
        }
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private long computePresentationTime(long frameIndex) {
        return 132 + frameIndex * 1000000 / mFrameRate;
    }
}
