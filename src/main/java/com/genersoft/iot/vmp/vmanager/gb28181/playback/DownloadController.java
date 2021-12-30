package com.genersoft.iot.vmp.vmanager.gb28181.playback;

import com.genersoft.iot.vmp.common.FileInfo;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.BusConfig;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.transmit.callback.DeferredResultHolder;
import com.genersoft.iot.vmp.gb28181.transmit.callback.RequestMessage;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.service.IPlayService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.apache.http.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.GsonBuilderUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.impl.SIPCommander;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import org.springframework.web.context.request.async.DeferredResult;

import javax.sip.header.CallIdHeader;
import java.text.SimpleDateFormat;
import java.util.UUID;

@Api(tags = "历史媒体下载")
@CrossOrigin
@RestController
@RequestMapping("/api/download")
public class DownloadController {

    private final static Logger logger = LoggerFactory.getLogger(DownloadController.class);
    @Autowired
    BusConfig busConfig;
    @Autowired
    private SIPCommander cmder;

    @Autowired
    private IVideoManagerStorager storager;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    // @Autowired
    // private ZLMRESTfulUtils zlmresTfulUtils;

    @Autowired
    private IPlayService playService;

    @Autowired
    private DeferredResultHolder resultHolder;

    @Autowired
    private IMediaServerService mediaServerService;

    @ApiOperation("开始历史媒体下载")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "deviceId", value = "设备ID", dataTypeClass = String.class),
            @ApiImplicitParam(name = "channelId", value = "通道ID", dataTypeClass = String.class),
            @ApiImplicitParam(name = "startTime", value = "开始时间", dataTypeClass = String.class),
            @ApiImplicitParam(name = "endTime", value = "结束时间", dataTypeClass = String.class),
            @ApiImplicitParam(name = "downloadSpeed", value = "下载倍速", dataTypeClass = String.class),
    })
    @GetMapping("/start/{deviceId}/{channelId}")
    public DeferredResult<ResponseEntity<String>> play(@PathVariable String deviceId, @PathVariable String channelId,
                                                       String startTime, String endTime, String downloadSpeed) {

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("历史媒体下载 API调用，deviceId：%s，channelId：%s，downloadSpeed：%s", deviceId, channelId, downloadSpeed));
        }
        String key = DeferredResultHolder.CALLBACK_CMD_DOWNLOAD + deviceId + channelId;
        String uuid = UUID.randomUUID().toString();
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>(30000L);
        // 超时处理
        result.onTimeout(() -> {
            logger.warn(String.format("设备下载响应超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeAllResult(msg);
        });
        if (resultHolder.exist(key, null)) {
            return result;
        }
        resultHolder.put(key, uuid, result);
        Device device = storager.queryVideoDevice(deviceId);
        StreamInfo streamInfo = redisCatchStorage.queryPlaybackByDevice(deviceId, channelId);
        if (streamInfo != null) {
            // 停止之前的下载
            cmder.streamByeCmd(VideoManagerConstants.VIDEO_DOWNLOAD,deviceId, channelId);
        }

        MediaServerItem newMediaServerItem = playService.getNewMediaServerItem(device);
        if (newMediaServerItem == null) {
            logger.warn(String.format("设备下载响应超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData("Timeout");
            resultHolder.invokeAllResult(msg);
            return result;
        }
        CallIdHeader callIdHeader = cmder.getCallIdHeader(device);
        String callId = callIdHeader.getCallId();
        callId = callId.substring(0, callId.indexOf("@"));
        SSRCInfo ssrcInfo = mediaServerService.openRTPServer(newMediaServerItem, null, true, null);

        cmder.downloadStreamCmd(newMediaServerItem, ssrcInfo, device, channelId, startTime, endTime, downloadSpeed, callIdHeader, (MediaServerItem mediaServerItem, JSONObject response) -> {
            logger.info("收到订阅消息： " + response.toJSONString());
            playService.onPublishHandlerForDownload(mediaServerItem, response, deviceId, channelId, uuid.toString());
        }, event -> {
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            msg.setData(String.format("回放失败， 错误码： %s, %s", event.statusCode, event.msg));
            resultHolder.invokeAllResult(msg);
        });

        return result;
    }

    @ApiOperation("停止历史媒体下载")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "deviceId", value = "设备ID", dataTypeClass = String.class),
            @ApiImplicitParam(name = "channelId", value = "通道ID", dataTypeClass = String.class),
    })
    @GetMapping("/stop/{deviceId}/{channelId}")
    public ResponseEntity<String> playStop(@PathVariable String deviceId, @PathVariable String channelId) {

        cmder.streamByeCmd(VideoManagerConstants.VIDEO_DOWNLOAD,deviceId, channelId);

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("设备历史媒体下载停止 API调用，deviceId/channelId：%s_%s", deviceId, channelId));
        }

        if (deviceId != null && channelId != null) {
            JSONObject json = new JSONObject();
            json.put("deviceId", deviceId);
            json.put("channelId", channelId);
            return new ResponseEntity<String>(json.toString(), HttpStatus.OK);
        } else {
            logger.warn("设备历史媒体下载停止API调用失败！");
            return new ResponseEntity<String>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @ApiOperation("下载历史录像")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "ip", value = "设备Ip", dataTypeClass = String.class),
            @ApiImplicitParam(name = "startTime", value = "开始时间", dataTypeClass = Long.class),
            @ApiImplicitParam(name = "endTime", value = "结束时间", dataTypeClass = Long.class),
            @ApiImplicitParam(name = "requestId", value = "唯一标识", dataTypeClass = String.class),
            @ApiImplicitParam(name = "deviceType", value = "设备类型(0,默认，国标设备， 1，海康, 2,大华)", dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "timeout", value = "下载超时时间，默认1分钟（单位毫秒,>1分钟）", dataTypeClass = Long.class),
    })
    @GetMapping("/startByIp/{ip}")
    public DeferredResult<ResponseEntity<String>> playByIp(@PathVariable String ip, long startTime, long endTime,
                                                           String requestId,int deviceType, long timeOut) {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("下载历史录像 API调用，ip：%s，requestId：%s", ip, requestId));
        }
        //先获取通道设备
        DeviceChannel deviceChannel = storager.queryChannelByIp(ip);
        String deviceId = null;
        String channelId = null;
        if (deviceChannel != null) {
            deviceId = deviceChannel.getDeviceId();
            channelId = deviceChannel.getChannelId();
        }
        String key = DeferredResultHolder.CALLBACK_CMD_DOWNLOAD + deviceId + "_"+channelId+"_"+requestId;
        String uuid = UUID.randomUUID().toString();
        if(timeOut <= 60000){
            timeOut = 60000L;
        }
        DeferredResult<ResponseEntity<String>> result = new DeferredResult<ResponseEntity<String>>(timeOut);
        // 超时处理
        String finalDeviceId = deviceId;
        String finalChannelId = channelId;
        result.onTimeout(() -> {
            logger.warn(String.format("设备下载响应超时，deviceId：%s ，channelId：%s", finalDeviceId, finalChannelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult fileInfoBaseData = new WVPResult();
            fileInfoBaseData.setMsg("下载超时");
            fileInfoBaseData.setCode(1);
            msg.setData(fileInfoBaseData);
            resultHolder.invokeAllResult(msg);
        });
        if (resultHolder.exist(key, null)) {
            return result;
        }
        resultHolder.put(key, uuid, result);
        if (TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(channelId)) {
            logger.warn(String.format("下载时，没有找到响应的设备，ip:%s,deviceId：%s ，channelId：%s", ip, deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult fileInfoBaseData = new WVPResult();
			fileInfoBaseData.setMsg("设备未找到");
			fileInfoBaseData.setCode(1);
			msg.setData(fileInfoBaseData);
            resultHolder.invokeAllResult(msg);
        }
        Device device = storager.queryVideoDevice(deviceId);
//		StreamInfo streamInfo = redisCatchStorage.queryPlaybackByDevice(deviceId, channelId);
//		if (streamInfo != null) {
//			// 停止之前的下载
//			cmder.streamByeCmd(deviceId, channelId);
//		}

        MediaServerItem newMediaServerItem = playService.getNewMediaServerItem(device);
        if (newMediaServerItem == null) {
            logger.warn(String.format("设备下载响应超时，deviceId：%s ，channelId：%s", deviceId, channelId));
            RequestMessage msg = new RequestMessage();
            msg.setId(uuid);
            msg.setKey(key);
            WVPResult fileInfoBaseData = new WVPResult<>();
			fileInfoBaseData.setMsg("下载超时");
			fileInfoBaseData.setCode(1);
			msg.setData(fileInfoBaseData);
            resultHolder.invokeAllResult(msg);
            return result;
        }
        //支持海康，并且是海康设备
        if(busConfig.isEnablehk() && deviceType == 1){
            //走海康的下载逻辑
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss");
            mediaServerService.downloadBackFile(newMediaServerItem, deviceId, Integer.parseInt(channelId.substring(channelId.length() - 3)),
                    format.format(startTime), format.format(endTime), new ZLMRESTfulUtils.UploadCallback() {
                        @Override
                        public void run(JSONObject response, String errormsg) {
                            RequestMessage msg = new RequestMessage();
                            msg.setId(uuid);
                            msg.setKey(key);
                            WVPResult fileInfoBaseData = new WVPResult();
                            if(response == null){
                                //下载失败
                            }else{

                            }
                            if(response==null){
                                fileInfoBaseData.setCode(-1);
                                fileInfoBaseData.setMsg(errormsg);
                            }else if(response.getInteger("code") == 0){
                                fileInfoBaseData.setCode(0);
                                fileInfoBaseData.setMsg("success");
                                JSONObject data = response.getJSONObject("data");
                                fileInfoBaseData.setData(new FileInfo(data.getString("fileName"),
                                        data.getString("bucketName"),data.getString("url")));
                            }else {
                                fileInfoBaseData.setCode(response.getInteger("code"));
                                fileInfoBaseData.setMsg(response.getString("msg"));
                            }
                            msg.setData(fileInfoBaseData);
                            resultHolder.invokeAllResult(msg);
                        }
                    });
        }else{
            CallIdHeader callIdHeader = cmder.getCallIdHeader(device);
            String callId = callIdHeader.getCallId();
            callId = callId.substring(0, callId.indexOf("@"));
            newMediaServerItem.setStreamId(requestId);
            SSRCInfo ssrcInfo = mediaServerService.openRTPServer(newMediaServerItem, requestId, true, callId);

            redisCatchStorage.startDownloadFile(callId,requestId);
            cmder.downloadBackFileCmd(newMediaServerItem, ssrcInfo, device, channelId, startTime / 1000, endTime / 1000, "8",
                    callIdHeader, (MediaServerItem mediaServerItem, JSONObject response) -> {
                        logger.info("收到订阅消息： " + response.toJSONString());
                        if(newMediaServerItem.getStreamId().equals(mediaServerItem.getStreamId())){
                            //返回下载结果
                            RequestMessage msg = new RequestMessage();
                            msg.setId(uuid);
                            msg.setKey(key);
                            WVPResult fileInfoBaseData = new WVPResult();
                            if(response.getInteger("code") == 0){
                                fileInfoBaseData.setCode(0);
                                fileInfoBaseData.setMsg("success");
                                JSONObject obj = response.getJSONObject("data");
                                JSONObject data = obj.getJSONObject("data");
                                fileInfoBaseData.setData(new FileInfo(data.getString("fileName"),
                                        data.getString("bucketName"),data.getString("url")));
                            }else {
                                fileInfoBaseData.setCode(response.getInteger("code"));
                                fileInfoBaseData.setMsg(response.getString("msg"));
                            }
                            msg.setData(fileInfoBaseData);
                            resultHolder.invokeAllResult(msg);
                        }
                    }, event -> {
                        RequestMessage msg = new RequestMessage();
                        msg.setId(uuid);
                        msg.setKey(key);
                        WVPResult fileInfoBaseData = new WVPResult();
                        fileInfoBaseData.setMsg("下载失败，"+event.msg);
                        fileInfoBaseData.setCode(event.statusCode);
                        msg.setData(fileInfoBaseData);
                        resultHolder.invokeAllResult(msg);
                    });
        }
        return result;
    }
}
