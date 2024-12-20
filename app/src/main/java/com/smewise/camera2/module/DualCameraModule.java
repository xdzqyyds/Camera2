package com.smewise.camera2.module;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.smewise.camera2.Config;
import com.smewise.camera2.R;
import com.smewise.camera2.callback.CameraUiEvent;
import com.smewise.camera2.callback.RequestCallback;
import com.smewise.camera2.manager.CameraSession;
import com.smewise.camera2.manager.CameraSettings;
import com.smewise.camera2.manager.Controller;
import com.smewise.camera2.manager.DeviceManager;
import com.smewise.camera2.manager.DualDeviceManager;
import com.smewise.camera2.manager.FocusOverlayManager;
import com.smewise.camera2.manager.Session;
import com.smewise.camera2.ui.DualCameraUI;
import com.smewise.camera2.utils.FileSaver;
import com.smewise.camera2.utils.MediaFunc;

import java.util.Arrays;

/**
 * Created by wenzhe on 16-3-8.
 */
public class DualCameraModule extends CameraModule implements FileSaver.FileListener {

    private static final String TAG = Config.getTag(DualCameraModule.class);

    private SurfaceTexture mainSurfaceTexture;
    private SurfaceTexture auxSurfaceTexture;
    private DualCameraUI mUI;
    private CameraSession mSession;
    private CameraSession mAuxSession;
    private DualDeviceManager mDeviceMgr;
    private FocusOverlayManager mFocusManager;
    private int mPicCount = 0;

    @Override
    protected void init() {
        mUI = new DualCameraUI(appContext, mainHandler, mCameraUiEvent);
        mUI.setCoverView(getCoverView());
        mFocusManager = new FocusOverlayManager(getBaseUI().getFocusView(), mainHandler.getLooper());
        mFocusManager.setListener(mCameraUiEvent);
        mDeviceMgr = new DualDeviceManager(appContext, getExecutor(), mCameraEvent);
        mSession = new CameraSession(appContext, mainHandler, getSettings());
        mAuxSession = new CameraSession(appContext, mainHandler, getSettings());
    }

    @Override
    public void start() {
        String[] idList = mDeviceMgr.getCameraIdList();
        
        if (idList != null) {
            for (String id : idList) {
                CameraCharacteristics characteristics = mDeviceMgr.getCharacteristics(id);
                
                if (characteristics != null) {
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    
                    int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean isLogicalCamera = false;
                    if (capabilities != null) {
                        for (int capability : capabilities) {
                            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                isLogicalCamera = true;
                                break;
                            }
                        }
                    }
                    
                    StringBuilder info = new StringBuilder();
                    info.append("\nCamera ID: ").append(id)
                        .append("\n  Facing: ").append(
                            facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" :
                            facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "EXTERNAL")
                        .append("\n  Is Logical Multi-Camera: ").append(isLogicalCamera);
                    
                    if (focalLengths != null) {
                        info.append("\n  Focal Lengths: ");
                        for (float focal : focalLengths) {
                            info.append(focal).append("mm, ");
                        }
                    }
                    
                    info.append("\n  Hardware Level: ")
                        .append(level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ? "LEGACY" :
                               level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED ? "LIMITED" :
                               level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL ? "FULL" :
                               level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 ? "LEVEL_3" : "UNKNOWN");

                    if (capabilities != null) {
                        info.append("\n  Capabilities: ");
                        for (int capability : capabilities) {
                            info.append("\n    - ").append(getCapabilityString(capability));
                        }
                    }
                    
                    Log.e(TAG, "Camera Info IDs: " + info.toString());
                }
            }
        }
        
        String mainId = getSettings().getGlobalPref(CameraSettings.KEY_MAIN_CAMERA_ID, idList != null && idList.length > 0 ? idList[0] : null);
        String auxId = getSettings().getGlobalPref(
                CameraSettings.KEY_AUX_CAMERA_ID, idList != null && idList.length > 1 ? idList[idList.length - 1] : null);
        mDeviceMgr.setCameraId(mainId, auxId);
        mDeviceMgr.openCamera(mainHandler);
        // when module changed , need update listener
        fileSaver.setFileListener(this);
        getBaseUI().setCameraUiEvent(mCameraUiEvent);
        addModuleView(mUI.getRootView());
        Log.e(TAG, "start module");
    }

    private DeviceManager.CameraEvent mCameraEvent = new DeviceManager.CameraEvent() {
        @Override
        public void onDeviceOpened(CameraDevice device) {
            super.onDeviceOpened(device);
            Log.e(TAG, "camera opened");
            mSession.applyRequest(Session.RQ_SET_DEVICE, device);
            enableState(Controller.CAMERA_STATE_OPENED);
            if (stateEnabled(Controller.CAMERA_STATE_UI_READY)) {
                mSession.applyRequest(Session.RQ_START_PREVIEW,mainSurfaceTexture, mRequestCallback);
                mAuxSession.applyRequest(Session.RQ_START_PREVIEW, auxSurfaceTexture, mAuxRequestCb);
            }
        }

        @Override
        public void onAuxDeviceOpened(CameraDevice device) {
            super.onAuxDeviceOpened(device);
            // method will be called before onDeviceOpened(CameraDevice device)
            mAuxSession.applyRequest(Session.RQ_SET_DEVICE, device, null);
        }

        @Override
        public void onDeviceClosed() {
            super.onDeviceClosed();
            disableState(Controller.CAMERA_STATE_OPENED);
            if (mUI != null) {
                mUI.resetFrameCount();
            }
            Log.e(TAG, "camera closed");
        }
    };

    private RequestCallback mRequestCallback = new RequestCallback() {
        @Override
        public void onDataBack(byte[] data, int width, int height) {
            super.onDataBack(data, width, height);
            saveFile(data, width, height, mDeviceMgr.getCameraId(true),
                    CameraSettings.KEY_PICTURE_FORMAT, "MAIN");
            enableUiAfterShot();
        }

        @Override
        public void onViewChange(int width, int height) {
            super.onViewChange(width, height);
            getBaseUI().updateUiSize(width, height);
            mUI.updateUISize(width, height);
            mFocusManager.onPreviewChanged(width, height, mDeviceMgr.getCharacteristics(true));
        }

        @Override
        public void onAFStateChanged(int state) {
            super.onAFStateChanged(state);
            updateAFState(state, mFocusManager);
        }
    };

    private RequestCallback mAuxRequestCb = new RequestCallback() {
        @Override
        public void onDataBack(byte[] data, int width, int height) {
            super.onDataBack(data, width, height);
            saveFile(data, width, height, mDeviceMgr.getCameraId(false),
                    CameraSettings.KEY_PICTURE_FORMAT, "AUX");
            enableUiAfterShot();
        }
    };

    private void enableUiAfterShot() {
        if (mPicCount == 2) {
            mUI.setUIClickable(true);
            getBaseUI().setUIClickable(true);
            mPicCount = 0;
            mSession.applyRequest(Session.RQ_RESTART_PREVIEW);
            mAuxSession.applyRequest(Session.RQ_RESTART_PREVIEW);
        }
    }

    @Override
    public void stop() {
        getBaseUI().setCameraUiEvent(null);
        getCoverView().showCover();
        mFocusManager.hideFocusUI();
        mFocusManager.removeDelayMessage();
        mSession.release();
        mAuxSession.release();
        mDeviceMgr.releaseCamera();
        Log.e(TAG, "stop module");
    }

    /**
     * FileSaver.FileListener
     * @param uri image file uri
     * @param path image file path
     * @param thumbnail image thumbnail
     */
    @Override
    public void onFileSaved(Uri uri, String path, Bitmap thumbnail) {
        MediaFunc.setCurrentUri(uri);
        mUI.setUIClickable(true);
        getBaseUI().setUIClickable(true);
        getBaseUI().setThumbnail(thumbnail);
    }

    /**
     * callback for file save error
     * @param msg error msg
     */
    @Override
    public void onFileSaveError(String msg) {
        Toast.makeText(appContext,msg, Toast.LENGTH_LONG).show();
        mUI.setUIClickable(true);
        getBaseUI().setUIClickable(true);
    }

    private void takePicture() {
        mUI.setUIClickable(false);
        getBaseUI().setUIClickable(false);
        mSession.applyRequest(Session.RQ_TAKE_PICTURE, getToolKit().getOrientation());
        mAuxSession.applyRequest(Session.RQ_TAKE_PICTURE, getToolKit().getOrientation());
    }

    private CameraUiEvent mCameraUiEvent = new CameraUiEvent() {

        @Override
        public void onPreviewUiReady(SurfaceTexture mainSurface, SurfaceTexture auxSurface) {
            Log.e(TAG, "onSurfaceTextureAvailable");
            mainSurfaceTexture = mainSurface;
            auxSurfaceTexture = auxSurface;
            enableState(Controller.CAMERA_STATE_UI_READY);
            if (stateEnabled(Controller.CAMERA_STATE_OPENED)) {
                mSession.applyRequest(Session.RQ_START_PREVIEW,mainSurfaceTexture, mRequestCallback);
                mAuxSession.applyRequest(Session.RQ_START_PREVIEW, auxSurfaceTexture, mAuxRequestCb);
            }
        }

        @Override
        public void onPreviewUiDestroy() {
            disableState(Controller.CAMERA_STATE_UI_READY);
            Log.e(TAG, "onSurfaceTextureDestroyed");
        }

        @Override
        public void onTouchToFocus(float x, float y) {
            mFocusManager.startFocus(x, y);
            MeteringRectangle focusRect = mFocusManager.getFocusArea(x, y, true);
            MeteringRectangle meterRect = mFocusManager.getFocusArea(x, y, false);
            mSession.applyRequest(Session.RQ_AF_AE_REGIONS, focusRect, meterRect);
            //mAuxSession.applyRequest(Session.RQ_AF_AE_REGIONS, focusRect, meterRect);
        }

        @Override
        public void resetTouchToFocus() {
            if (stateEnabled(Controller.CAMERA_MODULE_RUNNING)) {
                mSession.applyRequest(Session.RQ_FOCUS_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                mAuxSession.applyRequest(Session.RQ_FOCUS_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            }
        }

        @Override
        public <T> void onSettingChange(CaptureRequest.Key<T> key, T value) {

        }

        @Override
        public <T> void onAction(String type, T value) {
            switch (type) {
                case CameraUiEvent.ACTION_CLICK:
                    handleClick((View) value);
                    break;
                case CameraUiEvent.ACTION_CHANGE_MODULE:
                    setNewModule((Integer) value);
                    break;
                case CameraUiEvent.ACTION_SWITCH_CAMERA:
                    break;
                case CameraUiEvent.ACTION_PREVIEW_READY:
                    getCoverView().hideCoverWithAnimation();
                    break;
                default:
                    break;
            }
        }
    };

    private void handleClick(View view) {
        switch (view.getId()) {
            case R.id.btn_shutter:
                takePicture();
                break;
            case R.id.btn_setting:
                showSetting();
                break;
            case R.id.thumbnail:
                MediaFunc.goToGallery(appContext);
                break;
        }
    }

    private String getCapabilityString(int capability) {
        switch (capability) {
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                return "BACKWARD_COMPATIBLE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                return "MANUAL_SENSOR";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                return "MANUAL_POST_PROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                return "RAW";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING:
                return "PRIVATE_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS:
                return "READ_SENSOR_SETTINGS";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE:
                return "BURST_CAPTURE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING:
                return "YUV_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
                return "DEPTH_OUTPUT";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                return "CONSTRAINED_HIGH_SPEED_VIDEO";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING:
                return "MOTION_TRACKING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                return "LOGICAL_MULTI_CAMERA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
                return "MONOCHROME";
            default:
                return "CAPABILITY_" + capability;
        }
    }
}
