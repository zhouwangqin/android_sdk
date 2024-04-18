package com.fangte.sdk.peer;

import com.fangte.sdk.FTEngine;
import com.fangte.sdk.util.FTLog;

import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RendererCommon;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.Collections;
import java.util.List;

import static com.fangte.sdk.FTBase.VIDEO_TRACK_ID;
import static com.fangte.sdk.FTBase.VIDEO_TRACK_TYPE;
import static org.webrtc.SessionDescription.Type.ANSWER;

import android.view.ViewGroup;

// 视频推流对象
public class FTPeerVideo {
    // 参数
    public String strUid = "";
    private String strMid = "";
    private String strSfu = "";
    // 上层对象
    public FTEngine mFTEngine = null;

    // 连接状态
    public int nLive = 0;
    // 退出标记
    private boolean bClose = false;

    // 临时变量
    private SessionDescription localSdp = null;

    // 回调对象
    private final PCObserver pcObserver = new PCObserver();
    private final SDPOfferObserver sdpOfferObserver = new SDPOfferObserver();
    private final SDPAnswerObserver sdpAnswerObserver = new SDPAnswerObserver();

    // peer对象
    private PeerConnection mPeerConnection = null;
    // sdp media 对象
    private MediaConstraints sdpMediaConstraints = null;

    // 视频参数
    private int nWidth = 160;
    private int nHeight = 120;
    private int nFrame = 15;
    private int nMinBps = 100;
    private int nMaxBps = 100;

    // 当前摄像头
    private int mIndex = 0;

    // 视频对象
    private boolean bCapture = false;
    private RtpSender localVideoSender = null;
    private VideoSource mVideoSource = null;
    private VideoTrack mVideoTrack = null;
    private VideoCapturer mVideoCapturer = null;
    private SurfaceTextureHelper mSurfaceTextureHelper = null;

    // 渲染对象
    private ViewGroup mVideoView = null;
    private SurfaceViewRenderer videoRenderer = null;
    private final ProxyVideoSink videoProxy = new ProxyVideoSink();

    // 视频流回调
    private static class ProxyVideoSink implements VideoSink {
        private VideoSink mVideoSink = null;
        synchronized private void setTarget(VideoSink target) {
            mVideoSink = target;
        }

        @Override
        public void onFrame(VideoFrame frame) {
            if (mVideoSink != null) {
                mVideoSink.onFrame(frame);
            }
        }
    }

    // 设置本地视频质量
    /*
     0 -- 120p  160*120*15   100kbps
     1 -- 240p  320*240*15   200kbps
     2 -- 360p  480*360*15   350kbps
     3 -- 480p  640*480*15   500kbps
     4 -- 540p  960*540*15   1Mbps
     5 -- 720p  1280*720*15  1.5Mbps
     6 -- 1080p 1920*1080*15 2Mbps
     */
    public void setVideoLevel(int nLevel) {
        if (nLevel == 0) {
            nWidth = 160;
            nHeight = 120;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 100;
        }
        if (nLevel == 1) {
            nWidth = 320;
            nHeight = 240;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 200;
        }
        if (nLevel == 2) {
            nWidth = 480;
            nHeight = 320;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 350;
        }
        if (nLevel == 3) {
            nWidth = 640;
            nHeight = 480;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 500;
        }
        if (nLevel == 4) {
            nWidth = 960;
            nHeight = 540;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 1000;
        }
        if (nLevel == 5) {
            nWidth = 1280;
            nHeight = 720;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 1500;
        }
        if (nLevel == 6) {
            nWidth = 1920;
            nHeight = 1080;
            nFrame = 15;
            nMinBps = 100;
            nMaxBps = 2000;
        }
    }

    // 设置视频渲染窗口
    public void setVideoRenderer(ViewGroup view) {
        mVideoView = view;
        if (mFTEngine.mHandler != null) {
            mFTEngine.mHandler.post(this::setRenderer);
        }
    }

    // 切换前后摄像头
    public void switchCapture(int nIndex) {
        if (nIndex != 0 && nIndex != 1) {
            return;
        }

        if (mIndex != nIndex) {
            if (mVideoCapturer != null && mVideoCapturer instanceof CameraVideoCapturer) {
                CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) mVideoCapturer;
                cameraVideoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(boolean isFrontCamera) {
                        mIndex = isFrontCamera ? 0 : 1;
                    }

                    @Override
                    public void onCameraSwitchError(String errorDescription) {

                    }
                });
            } else {
                mIndex = nIndex;
            }
        }
    }

    // 获取前后摄像头
    public int getCaptureIndex() {
        return mIndex;
    }

    // 启动推流
    public void startPublish() {
        if (mFTEngine.mHandler != null) {
            mFTEngine.mHandler.post(this::initRenderer);
        }
        initCapture();
        initPeerConnection();
        createOffer();
    }

    // 取消推流
    public void stopPublish() {
        sendUnPublish();
        freePeerConnection();
        freeCapture();
        if (mFTEngine.mHandler != null) {
            mFTEngine.mHandler.post(this::freeRenderer);
        }
    }

    // 设置摄像头设备可用或者禁用
    public void setVideoEnable(boolean bEnable) {
        if (mVideoTrack != null) {
            mVideoTrack.setEnabled(bEnable);
        }
    }

    // 初始化视频渲染
    private void initRenderer() {
        freeRenderer();
        videoRenderer = new SurfaceViewRenderer(mFTEngine.mContext);
        videoRenderer.init(mFTEngine.mEglBase.getEglBaseContext(), null);
        videoRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        videoRenderer.setEnableHardwareScaler(false);
        videoRenderer.setMirror(true);
        videoProxy.setTarget(videoRenderer);
        if (mVideoView != null) {
            mVideoView.addView(videoRenderer);
        }
    }

    // 释放视频渲染
    private void freeRenderer() {
        videoProxy.setTarget(null);
        if (videoRenderer != null) {
            videoRenderer.release();
            videoRenderer = null;
        }
    }

    // 设置渲染对象
    private void setRenderer() {
        mVideoView.removeAllViews();
        if (videoRenderer != null) {
            if (videoRenderer.getParent() != null) {
                ((ViewGroup) videoRenderer.getParent()).removeView(videoRenderer);
            }
            mVideoView.addView(videoRenderer);
        }
    }

    // 初始化视频采集
    private void initCapture() {
        freeCapture();
        initCameraCapture();
    }

    // 释放视频采集
    private void freeCapture() {
        stopVideoSource();
        freeCameraCapture();
    }

    // 初始化摄像头采集
    private void initCameraCapture() {
        mVideoCapturer = createCameraCapturer(new Camera1Enumerator(true));
    }

    // 释放摄像头采集
    private void freeCameraCapture() {
        if (mVideoCapturer != null) {
            mVideoCapturer.dispose();
            mVideoCapturer = null;
        }
    }

    // 创建摄像头采集对象
    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();
        // First, try to find front facing camera
        if (mIndex == 0) {
            for (String deviceName : deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
        } else {
            for (String deviceName : deviceNames) {
                if (enumerator.isBackFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
        }
        return null;
    }

    // 启动视频采集
    private void startVideoSource() {
        if (mVideoCapturer != null && !bCapture) {
            FTLog.e("FTPeerVideo startVideoSource");
            mVideoCapturer.startCapture(nWidth, nHeight, nFrame);
            bCapture = true;
        }
    }

    // 停止视频采集
    private void stopVideoSource() {
        if (mVideoCapturer != null && bCapture) {
            FTLog.e("FTPeerVideo stopVideoSource");
            try {
                mVideoCapturer.stopCapture();
                bCapture = false;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    // 创建PeerConnection
    private void initPeerConnection() {
        freePeerConnection();
        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        // 创建PeerConnect对象
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(mFTEngine.iceServers);
        rtcConfig.disableIpv6 = true;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        mPeerConnection = mFTEngine.mPeerConnectionFactory.createPeerConnection(rtcConfig, pcObserver);
        // 添加Track
        if (mVideoCapturer != null) {
            createVideoTrack(mVideoCapturer);
            List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");
            mPeerConnection.addTrack(mVideoTrack, mediaStreamLabels);
            startVideoSource();
            findVideoSender();
        }
        // 状态赋值
        nLive = 1;
        bClose = false;
    }

    // 删除PeerConnection
    private void freePeerConnection() {
        if (bClose) {
            return;
        }

        nLive = 0;
        bClose = true;
        localSdp = null;
        localVideoSender = null;
        if (mVideoTrack != null) {
            mVideoTrack.dispose();
            mVideoTrack = null;
        }
        if (mVideoSource != null) {
            mVideoSource.dispose();
            mVideoSource = null;
        }
        if (mSurfaceTextureHelper != null) {
            //mSurfaceTextureHelper.dispose();
            mSurfaceTextureHelper = null;
        }
        if (mPeerConnection != null) {
            mPeerConnection.dispose();
            mPeerConnection = null;
        }
    }

    // 创建本地视频Track
    private void createVideoTrack(VideoCapturer videoCapturer) {
        mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mFTEngine.mEglBase.getEglBaseContext());
        mVideoSource = mFTEngine.mPeerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(mSurfaceTextureHelper, mFTEngine.mContext, mVideoSource.getCapturerObserver());
        mVideoTrack = mFTEngine.mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, mVideoSource);
        mVideoTrack.setEnabled(mFTEngine.bVideoEnable);
        mVideoTrack.addSink(videoProxy);
    }

    // 查询视频的Send
    private void findVideoSender() {
        for (RtpSender sender : mPeerConnection.getSenders()) {
            MediaStreamTrack mediaStreamTrack = sender.track();
            if (mediaStreamTrack != null) {
                String trackType = mediaStreamTrack.kind();
                if (trackType.equals(VIDEO_TRACK_TYPE)) {
                    localVideoSender = sender;
                }
            }
        }
    }

    // 设置视频参数
    private void setVideoBitrate() {
        if (mPeerConnection == null || localVideoSender == null) {
            return;
        }

        RtpParameters parameters = localVideoSender.getParameters();
        if (parameters.encodings.size() == 0) {
            FTLog.e("RtpParameters are not ready.");
            return;
        }

        for (RtpParameters.Encoding encoding : parameters.encodings) {
            encoding.minBitrateBps = nMinBps * 1000;
            encoding.maxBitrateBps = nMaxBps * 1000;
            encoding.maxFramerate = 15;
        }

        if (!localVideoSender.setParameters(parameters)) {
            FTLog.e("RtpSender.setParameters failed.");
        }
    }

    // 创建Offer SDP
    private void createOffer() {
        if (mPeerConnection != null) {
            mPeerConnection.createOffer(sdpOfferObserver, sdpMediaConstraints);
        }
    }

    // 推流
    private void sendPublish(final SessionDescription sdp) {
        if (bClose) {
            return;
        }
        new Thread(() -> {
            if (mFTEngine != null && sdp != null) {
                if (mFTEngine.mFTClient.SendPublish(sdp.description, false, true, 0, 0)) {
                    nLive = 2;
                    // 处理返回
                    strMid = mFTEngine.mFTClient.strMid;
                    strSfu = mFTEngine.mFTClient.sfuId;
                    SessionDescription mSessionDescription = new SessionDescription(ANSWER, mFTEngine.mFTClient.strSdp);
                    onRemoteDescription(mSessionDescription);
                    return;
                }
            }
            if (bClose) {
                return;
            }
            nLive = 0;
        }).start();
    }

    // 取消推流
    private void sendUnPublish() {
        if (strMid.equals("")) {
            return;
        }
        new Thread(() -> {
            mFTEngine.mFTClient.SendUnpublish(strMid, strSfu);
            strMid = "";
            strSfu = "";
        }).start();
    }

    // 设置本地offer sdp回调处理
    private void onLocalDescription(final SessionDescription sdp) {
        setVideoBitrate();
        sendPublish(sdp);
    }

    // 接受到远端answer sdp处理
    private void onRemoteDescription(final SessionDescription sdp) {
        FTLog.e("FTPeerVideo setRemoteDescription = " + strMid);
        if (mPeerConnection != null) {
            mPeerConnection.setRemoteDescription(sdpAnswerObserver, sdp);
        }
    }

    // 连接回调
    private class PCObserver implements PeerConnection.Observer {

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onStandardizedIceConnectionChange(PeerConnection.IceConnectionState newState) {

        }

        @Override
        public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
            FTLog.e("FTPeerVideo PeerConnection.Observer onConnectionChange = " + newState);
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                nLive = 4;
            }
            if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) {
                nLive = 0;
            }
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                nLive = 0;
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {

        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {

        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {

        }
    }

    // 设置offer sdp的回调
    private class SDPOfferObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription origSdp) {
            FTLog.e("FTPeerVideo create offer sdp ok");
            if (bClose) {
                return;
            }
            localSdp = origSdp;
            if (mPeerConnection != null) {
                mPeerConnection.setLocalDescription(sdpOfferObserver, origSdp);
            }
        }

        @Override
        public void onSetSuccess() {
            FTLog.e("FTPeerVideo set offer sdp ok");
            if (bClose) {
                return;
            }
            onLocalDescription(localSdp);
        }

        @Override
        public void onCreateFailure(final String error) {
            FTLog.e("FTPeerVideo create offer sdp fail = " + error);
            nLive = 0;
        }

        @Override
        public void onSetFailure(final String error) {
            FTLog.e("FTPeerVideo offer sdp set error = " + error);
            nLive = 0;
        }
    }

    // 设置answer sdp的回调
    private class SDPAnswerObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(final SessionDescription origSdp) {

        }

        @Override
        public void onSetSuccess() {
            FTLog.e("FTPeerVideo set remote sdp ok");
            if (bClose) {
                return;
            }
            nLive = 3;
        }

        @Override
        public void onCreateFailure(final String error) {

        }

        @Override
        public void onSetFailure(final String error) {
            FTLog.e("FTPeerVideo answer sdp set error = " + error);
            nLive = 0;
        }
    }
}
