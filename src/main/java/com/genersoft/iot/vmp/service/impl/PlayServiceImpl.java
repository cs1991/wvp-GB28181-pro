package com.genersoft.iot.vmp.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetup;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.session.VideoStreamSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.media.zlm.ZLMHttpHookSubscribe;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.media.zlm.dto.StreamProxyItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IStreamProxyService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.genersoft.iot.vmp.vmanager.gb28181.play.bean.PlayResult;
import com.genersoft.iot.vmp.service.IMediaService;
import com.genersoft.iot.vmp.service.IPlayService;
import gov.nist.javax.sip.stack.SIPDialog;
import org.apache.http.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.FileNotFoundException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings(value = {"rawtypes", "unchecked"})
@Service
public class PlayServiceImpl implements IPlayService {

    private final static Logger logger = LoggerFactory.getLogger(PlayServiceImpl.class);
    @Autowired
    private Environment env;
    @Autowired
    private IStreamProxyService streamProxyService;
    @Autowired
    private IVideoManagerStorager storager;

    @Autowired
    private SIPCommander cmder;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private RedisUtil redis;

    @Autowired
    private DeferredResultHolder resultHolder;

    @Autowired
    private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private IMediaService mediaService;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private VideoStreamSessionManager streamSession;

    @Autowired
    private UserSetup userSetup;


    @Override
    public DeferredResult<ResponseEntity<String>> playStop(String deviceId, String channelId) {
        logger.debug(String.format("playStop 设备预览/回放停止API调用，streamId：%s_%s", deviceId, channelId));

        String uuid = UUID.randomUUID().toString();
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>();

        // 录像查询以channelId作为deviceId查询
        String key = DeferredResultHolder.CALLBACK_CMD_STOP + deviceId + channelId;
        resultHolder.put(key, uuid, result);
        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(channelId)) {
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("点播未找到");
            resultHolder.invokeAllResult(msg);
            return result;
        }

        Device device = storager.queryVideoDevice(deviceId);
        cmder.streamByeCmd(VideoManagerConstants.VIDEO_PREVIEW, deviceId, channelId, (event) -> {
            StreamInfo streamInfo = redisCatchStorage.queryPlayByDevice(deviceId, channelId);
            if (streamInfo == null) {
                RequestMessage msg = new RequestMessage();
                msg.setId(uuid);
                msg.setKey(key);
                msg.setData("点播未找到");
                resultHolder.invokeAllResult(msg);
                storager.stopPlay(deviceId, channelId);
            } else {
                redisCatchStorage.stopPlay(streamInfo);
                storager.stopPlay(streamInfo.getDeviceID(), streamInfo.getChannelId());
                RequestMessage msg = new RequestMessage();
                msg.setId(uuid);
                msg.setKey(key);
                //Response response = event.getResponse();
                msg.setData(String.format("success"));
                resultHolder.invokeAllResult(msg);
            }
            mediaServerService.closeRTPServer(device, channelId, VideoManagerConstants.VIDEO_PREVIEW);
        });

        if (deviceId != null || channelId != null) {
            JSONObject json = new JSONObject();
            json.put("deviceId", deviceId);
            json.put("channelId", channelId);
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData(json.toString());
            resultHolder.invokeAllResult(msg);
        } else {
            logger.warn("设备预览/回放停止API调用失败！");
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("streamId null");
            resultHolder.invokeAllResult(msg);
        }

        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备预览/回放停止超时，deviceId/channelId：%s_%s ", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeAllResult(msg);
        });
        return result;
    }

    @Override
    public PlayResult play(MediaServerItem mediaServerItem, String deviceId, String channelId, ZLMHttpHookSubscribe.Event hookEvent, SipSubscribe.Event errorEvent) {
        PlayResult playResult = new PlayResult();
        RequestMessage msg = new RequestMessage();
        String key = DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId;
        msg.setKey(key);
        String uuid = UUID.randomUUID().toString();
        msg.setId(uuid);
        playResult.setUuid(uuid);
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<>(userSetup.getPlayTimeout());
        playResult.setResult(result);
        // 录像查询以channelId作为deviceId查询
        resultHolder.put(key, uuid, result);
        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(channelId)) {
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            wvpResult.setMsg("没有找到该设备或设备不在线");
            msg.setData(wvpResult);
            resultHolder.invokeResult(msg);
            return playResult;
        }
        if (mediaServerItem == null) {
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            wvpResult.setMsg("未找到可用的zlm");
            msg.setData(wvpResult);
            resultHolder.invokeResult(msg);
            return playResult;
        }
        Device device = storager.queryVideoDevice(deviceId);
        StreamInfo streamInfo = redisCatchStorage.queryPlayByDevice(deviceId, channelId);
        playResult.setDevice(device);
        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备点播超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            SIPDialog dialog = streamSession.getDialog(VideoManagerConstants.VIDEO_PREVIEW, deviceId, channelId);
            if (dialog != null) {
                wvpResult.setMsg("收流超时，请稍候重试");
            } else {
                wvpResult.setMsg("点播超时，请稍候重试");
            }
            msg.setData(wvpResult);
            // 点播超时回复BYE
            cmder.streamByeCmd(VideoManagerConstants.VIDEO_PREVIEW, device.getDeviceId(), channelId);
            // 释放rtpserver
            mediaServerService.closeRTPServer(playResult.getDevice(), channelId, VideoManagerConstants.VIDEO_PREVIEW);
            // 回复之前所有的点播请求
            resultHolder.invokeAllResult(msg);
        });
        result.onCompletion(() -> {
            // 点播结束时调用截图接口
            try {
                String classPath = ResourceUtils.getURL("classpath:").getPath();
                // System.out.println(classPath);
                // 兼容打包为jar的class路径
                if (classPath.contains("jar")) {
                    classPath = classPath.substring(0, classPath.lastIndexOf("."));
                    classPath = classPath.substring(0, classPath.lastIndexOf("/") + 1);
                }
                if (classPath.startsWith("file:")) {
                    classPath = classPath.substring(classPath.indexOf(":") + 1);
                }
                String path = classPath + "static/static/snap/";
                // 兼容Windows系统路径（去除前面的“/”）
                if (System.getProperty("os.name").contains("indows")) {
                    path = path.substring(1);
                }
                String fileName = deviceId + "_" + channelId + ".jpg";
                ResponseEntity responseEntity = (ResponseEntity) result.getResult();
                if (responseEntity != null && responseEntity.getStatusCode() == HttpStatus.OK) {
                    WVPResult wvpResult = (WVPResult) responseEntity.getBody();
                    if (Objects.requireNonNull(wvpResult).getCode() == 0) {
                        StreamInfo streamInfoForSuccess = (StreamInfo) wvpResult.getData();
                        MediaServerItem mediaInfo = mediaServerService.getOne(streamInfoForSuccess.getMediaServerId());
                        String streamUrl = streamInfoForSuccess.getFmp4();
                        // 请求截图
                        zlmresTfulUtils.getSnap(mediaInfo, streamUrl, 15, 1, path, fileName);
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        });
        if (streamInfo == null) {
            SSRCInfo ssrcInfo;
            String streamId = null;
            if (mediaServerItem.isRtpEnable()) {
                streamId = String.format("%s_%s", device.getDeviceId(), channelId);
            }

            ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, streamId);

            // 发送点播消息
            cmder.playStreamCmd(mediaServerItem, ssrcInfo, device, channelId, (MediaServerItem mediaServerItemInUse, JSONObject response) -> {
                logger.info("收到订阅消息： " + response.toJSONString());
                onPublishHandlerForPlay(mediaServerItemInUse, response, deviceId, channelId, uuid);
                if (hookEvent != null) {
                    hookEvent.response(mediaServerItem, response);
                }
            }, (event) -> {
                WVPResult wvpResult = new WVPResult();
                wvpResult.setCode(-1);
                // 点播返回sip错误
                mediaServerService.closeRTPServer(playResult.getDevice(), channelId, VideoManagerConstants.VIDEO_PREVIEW);
                wvpResult.setMsg(String.format("点播失败， 错误码： %s, %s", event.statusCode, event.msg));
                msg.setData(wvpResult);
                resultHolder.invokeAllResult(msg);
                if (errorEvent != null) {
                    errorEvent.response(event);
                }


            });
        } else {
            String streamId = streamInfo.getStreamId();
            if (streamId == null) {
                WVPResult wvpResult = new WVPResult();
                wvpResult.setCode(-1);
                wvpResult.setMsg("点播失败， redis缓存streamId等于null");
                msg.setData(wvpResult);
                resultHolder.invokeAllResult(msg);
                return playResult;
            }
            String mediaServerId = streamInfo.getMediaServerId();
            MediaServerItem mediaInfo = mediaServerService.getOne(mediaServerId);

            JSONObject rtpInfo = zlmresTfulUtils.getRtpInfo(mediaInfo, streamId);
            if (rtpInfo != null && rtpInfo.getBoolean("exist")) {

                WVPResult wvpResult = new WVPResult();
                wvpResult.setCode(0);
                wvpResult.setMsg("success");
                wvpResult.setData(streamInfo);
                msg.setData(wvpResult);

                resultHolder.invokeAllResult(msg);
                if (hookEvent != null) {
                    hookEvent.response(mediaServerItem, JSONObject.parseObject(JSON.toJSONString(streamInfo)));
                }
            } else {
                // TODO 点播前是否重置状态
                redisCatchStorage.stopPlay(streamInfo);
                storager.stopPlay(streamInfo.getDeviceID(), streamInfo.getChannelId());
                SSRCInfo ssrcInfo;
                String streamId2 = null;
                if (mediaServerItem.isRtpEnable()) {
                    streamId2 = String.format("%s_%s", device.getDeviceId(), channelId);
                }
                ssrcInfo = mediaServerService.openRTPServer(mediaServerItem, streamId2);

                cmder.playStreamCmd(mediaServerItem, ssrcInfo, device, channelId, (MediaServerItem mediaServerItemInuse, JSONObject response) -> {
                    logger.info("收到订阅消息： " + response.toJSONString());
                    onPublishHandlerForPlay(mediaServerItemInuse, response, deviceId, channelId, uuid);
                }, (event) -> {
                    mediaServerService.closeRTPServer(playResult.getDevice(), channelId, VideoManagerConstants.VIDEO_PREVIEW);
                    WVPResult wvpResult = new WVPResult();
                    wvpResult.setCode(-1);
                    wvpResult.setMsg(String.format("点播失败， 错误码： %s, %s", event.statusCode, event.msg));
                    msg.setData(wvpResult);
                    resultHolder.invokeAllResult(msg);
                });
            }
        }

        return playResult;
    }

    private String formatHkDate(String time){
        return time.replace("-","").replace(":","").replace(" ","t") + "z";
    }
    @Override
    public DeferredResult<ResponseEntity<String>> playBackHk(String deviceId, String channelId,
                                                             String startTime, String endTime) {
        String uuid = UUID.randomUUID().toString();
        String key = DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId;
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>(30000L);
        resultHolder.put(DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId, uuid, result);

        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(channelId)) {
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            wvpResult.setMsg("没有找到该设备或设备不在线");
            msg.setData(wvpResult);
            resultHolder.invokeResult(msg);
            return result;
        }

        Device device = storager.queryVideoDevice(deviceId);
        if (device == null) {
            result.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            return result;
        }
        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备回放超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeResult(msg);
        });
        MediaServerItem mediaInfo = getNewMediaServerItem(device);
        if (mediaInfo == null) {
            logger.warn(String.format("设备回放失败，没有找到可用流媒体节点，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            wvpResult.setMsg("设备回放失败，没有找到可用流媒体节点");
            msg.setData(wvpResult);
            resultHolder.invokeResult(msg);
            return result;
        }
        //2021-12-29%2023:06:46
        StreamProxyItem proxyItem = new StreamProxyItem();
        //rtsp://admin:Aa88888888@192.168.3.168:554/Streaming/tracks/201?starttime=20211214t063800z&endtime=20211214t063811z
        String userName = env.getProperty("devices."+deviceId+".user");
        String pwd = env.getProperty("devices."+deviceId+".pwd");
        String port = env.getProperty("devices."+deviceId+".rtsp-port");
        String ip = env.getProperty("devices."+deviceId+".ip");
        String rtspUrl = new StringBuilder().append("rtsp://").append(userName).append(":").append(pwd).append("@").append(ip).append(":").append(port)
                .append("/Streaming/tracks/").append(Integer.parseInt(channelId.substring(channelId.length() - 3))).append("01?starttime=").append(formatHkDate(startTime))
                .append("&endtime=").append(formatHkDate(endTime)).toString();
        proxyItem.setUrl(rtspUrl);
        proxyItem.setApp("backhls");
        proxyItem.setStream(channelId);
        proxyItem.setName("backhls");
        proxyItem.setType("default");
        String dstUrl = String.format("rtmp://%s:%s/%s/%s", "127.0.0.1", mediaInfo.getRtmpPort(), proxyItem.getApp(),
                proxyItem.getStream());
        proxyItem.setDst_url(dstUrl);
        proxyItem.setMediaServerId(mediaInfo.getId());

        JSONObject jsonObject = streamProxyService.addStreamProxyToZlm(proxyItem);
        RequestMessage msg = new RequestMessage();
        msg.setId(uuid);
        msg.setKey(key);
        WVPResult wvpResult = new WVPResult();
        if (jsonObject == null) {
            wvpResult.setCode(-1);
            wvpResult.setMsg("设备回放失败，没有找到可用流媒体节点");
        }else {
            StreamInfo streamInfo = mediaService.getStreamInfoByAppAndStream(
                    mediaInfo, proxyItem.getApp(), proxyItem.getStream(), null);
            wvpResult.setCode(0);
            wvpResult.setMsg("success");
            wvpResult.setData(streamInfo);
        }
        msg.setData(wvpResult);
        resultHolder.invokeResult(msg);
        return result;
    }

    @Override
    public DeferredResult<ResponseEntity<String>> playBack(String deviceId, String channelId,
                                                           String startTime, String endTime) {
        String uuid = UUID.randomUUID().toString();
        String key = DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId;
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>(30000L);
        resultHolder.put(DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId, uuid, result);

        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(channelId)) {
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(-1);
            wvpResult.setMsg("没有找到该设备或设备不在线");
            msg.setData(wvpResult);
            resultHolder.invokeResult(msg);
            return result;
        }

        Device device = storager.queryVideoDevice(deviceId);
        if (device == null) {
            result.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
            return result;
        }
        MediaServerItem newMediaServerItem = getNewMediaServerItem(device);
        SSRCInfo ssrcInfo = mediaServerService.openRTPServer(newMediaServerItem, null, true, null);

        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备回放超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeResult(msg);
        });

        StreamInfo streamInfo = redisCatchStorage.queryPlaybackByDevice(deviceId, channelId);
        if (streamInfo != null) {
            // 停止之前的回放
            cmder.streamByeCmd(VideoManagerConstants.VIDEO_PLAYBACK, deviceId, channelId);
        }


        if (newMediaServerItem == null) {
            logger.warn(String.format("设备回放超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeResult(msg);
            return result;
        }

        cmder.playbackStreamCmd(newMediaServerItem, ssrcInfo, device, channelId, startTime, endTime, (MediaServerItem mediaServerItem, JSONObject response) -> {
            logger.info("收到订阅消息： " + response.toJSONString());
            onPublishHandlerForPlayBack(mediaServerItem, response, deviceId, channelId, uuid.toString());
        }, event -> {
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData(String.format("回放失败， 错误码： %s, %s", event.statusCode, event.msg));
            resultHolder.invokeResult(msg);
        });

        return result;
    }

    @Override
    public void onPublishHandlerForPlay(MediaServerItem mediaServerItem, JSONObject resonse, String deviceId, String channelId, String uuid) {
        RequestMessage msg = new RequestMessage();
        msg.setId(uuid);
        msg.setKey(DeferredResultHolder.CALLBACK_CMD_PLAY + deviceId + channelId);
        StreamInfo streamInfo = onPublishHandler(mediaServerItem, resonse, deviceId, channelId, uuid);
        if (streamInfo != null) {
            DeviceChannel deviceChannel = storager.queryChannel(deviceId, channelId);
            if (deviceChannel != null) {
                deviceChannel.setStreamId(streamInfo.getStreamId());
                storager.startPlay(deviceId, channelId, streamInfo.getStreamId());
            }
            redisCatchStorage.startPlay(streamInfo);
            msg.setData(JSON.toJSONString(streamInfo));

            WVPResult wvpResult = new WVPResult();
            wvpResult.setCode(0);
            wvpResult.setMsg("success");
            wvpResult.setData(streamInfo);
            msg.setData(wvpResult);

            resultHolder.invokeAllResult(msg);
        } else {
            logger.warn("设备预览API调用失败！");
            msg.setData("设备预览API调用失败！");
            resultHolder.invokeAllResult(msg);
        }
    }

    @Override
    public MediaServerItem getNewMediaServerItem(Device device) {
        if (device == null) return null;
        String mediaServerId = device.getMediaServerId();
        MediaServerItem mediaServerItem;
        if (mediaServerId == null) {
            mediaServerItem = mediaServerService.getMediaServerForMinimumLoad();
        } else {
            mediaServerItem = mediaServerService.getOne(mediaServerId);
        }
        if (mediaServerItem == null) {
            logger.warn("点播时未找到可使用的ZLM...");
        }
        return mediaServerItem;
    }


    @Override
    public void onPublishHandlerForPlayBack(MediaServerItem mediaServerItem, JSONObject resonse, String deviceId, String channelId, String uuid) {
        RequestMessage msg = new RequestMessage();
        msg.setKey(DeferredResultHolder.CALLBACK_CMD_PLAYBACK + deviceId + channelId);
        msg.setId(uuid);
        StreamInfo streamInfo = onPublishHandler(mediaServerItem, resonse, deviceId, channelId, uuid);
        if (streamInfo != null) {
            redisCatchStorage.startPlayback(streamInfo);
            msg.setData(JSON.toJSONString(streamInfo));
            resultHolder.invokeResult(msg);
        } else {
            logger.warn("设备回放API调用失败！");
            msg.setData("设备回放API调用失败！");
            resultHolder.invokeResult(msg);
        }
    }


    @Override
    public void onPublishHandlerForDownload(MediaServerItem mediaServerItem, JSONObject response, String deviceId, String channelId, String uuid) {
        RequestMessage msg = new RequestMessage();
        msg.setKey(DeferredResultHolder.CALLBACK_CMD_DOWNLOAD + deviceId + channelId);
        msg.setId(uuid);
        StreamInfo streamInfo = onPublishHandler(mediaServerItem, response, deviceId, channelId, uuid);
        if (streamInfo != null) {
            redisCatchStorage.startDownload(streamInfo);
            msg.setData(JSON.toJSONString(streamInfo));
            resultHolder.invokeResult(msg);
        } else {
            logger.warn("设备预览API调用失败！");
            msg.setData("设备预览API调用失败！");
            resultHolder.invokeResult(msg);
        }
    }


    public StreamInfo onPublishHandler(MediaServerItem mediaServerItem, JSONObject resonse, String deviceId, String channelId, String uuid) {
        String streamId = resonse.getString("stream");
        JSONArray tracks = resonse.getJSONArray("tracks");
        StreamInfo streamInfo = mediaService.getStreamInfoByAppAndStream(mediaServerItem, "rtp", streamId, tracks);
        streamInfo.setDeviceID(deviceId);
        streamInfo.setChannelId(channelId);
        return streamInfo;
    }

}
