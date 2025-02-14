package com.genersoft.iot.vmp.storager.impl;

import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetup;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.*;

@SuppressWarnings("rawtypes")
@Component
public class RedisCatchStorageImpl implements IRedisCatchStorage {
    private Logger logger = LoggerFactory.getLogger(RedisCatchStorageImpl.class);
    @Autowired
    private RedisUtil redis;

    @Autowired
    private DeviceChannelMapper deviceChannelMapper;
    @Autowired
    private UserSetup userSetup;
    private SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public boolean startDownloadFile(String callId, String streamId) {
        return redis.set(callId, streamId);
    }

    @Override
    public boolean stopDownloadFile(String callId) {
        return redis.del(callId);
    }

    /**
     * 查询播放列表
     *
     * @return
     */
    @Override
    public String queryDownload(String callId) {
        return (String) redis.get(callId);
    }

    /**
     * 开始播放时将流存入redis
     *
     * @return
     */
    @Override
    public boolean startPlay(StreamInfo stream) {
        return redis.set(String.format("%S_%S_%s_%s_%s", VideoManagerConstants.PLAYER_PREFIX, userSetup.getServerId(), stream.getStreamId(), stream.getDeviceID(), stream.getChannelId()),
                stream);
    }

    /**
     * 停止播放时从redis删除
     *
     * @return
     */
    @Override
    public boolean stopPlay(StreamInfo streamInfo) {
        if (streamInfo == null) return false;
        return redis.del(String.format("%S_%s_%s_%s_%s", VideoManagerConstants.PLAYER_PREFIX,
                userSetup.getServerId(),
                streamInfo.getStreamId(),
                streamInfo.getDeviceID(),
                streamInfo.getChannelId()));
    }

    /**
     * 查询播放列表
     *
     * @return
     */
    @Override
    public StreamInfo queryPlay(StreamInfo streamInfo) {
        return (StreamInfo) redis.get(String.format("%S_%s_%s_%s_%s",
                VideoManagerConstants.PLAYER_PREFIX,
                userSetup.getServerId(),
                streamInfo.getStreamId(),
                streamInfo.getDeviceID(),
                streamInfo.getChannelId()));
    }

    @Override
    public StreamInfo queryPlayByStreamId(String streamId) {
        List<Object> playLeys = redis.scan(String.format("%S_%s_%s_*", VideoManagerConstants.PLAYER_PREFIX, userSetup.getServerId(), streamId));
        if (playLeys == null || playLeys.size() == 0) return null;
        return (StreamInfo) redis.get(playLeys.get(0).toString());
    }

    @Override
    public StreamInfo queryPlaybackByStreamId(String streamId) {
        List<Object> playLeys = redis.scan(String.format("%S_%s_%s_*", VideoManagerConstants.PLAY_BLACK_PREFIX, userSetup.getServerId(), streamId));
        if (playLeys == null || playLeys.size() == 0) return null;
        return (StreamInfo) redis.get(playLeys.get(0).toString());
    }

    @Override
    public StreamInfo queryPlayByDevice(String deviceId, String channelId) {
        List<Object> playLeys = redis.scan(String.format("%S_%s_*_%s_%s", VideoManagerConstants.PLAYER_PREFIX,
                userSetup.getServerId(),
                deviceId,
                channelId));
        if (playLeys == null || playLeys.size() == 0) return null;
        return (StreamInfo) redis.get(playLeys.get(0).toString());
    }

    @Override
    public Map<String, StreamInfo> queryPlayByDeviceId(String deviceId) {
        Map<String, StreamInfo> streamInfos = new HashMap<>();
//		List<Object> playLeys = redis.keys(String.format("%S_*_%S_*", VideoManagerConstants.PLAYER_PREFIX, deviceId));
        List<Object> players = redis.scan(String.format("%S_%s_*_%S_*", VideoManagerConstants.PLAYER_PREFIX, userSetup.getServerId(), deviceId));
        if (players.size() == 0) return streamInfos;
        for (int i = 0; i < players.size(); i++) {
            String key = (String) players.get(i);
            StreamInfo streamInfo = (StreamInfo) redis.get(key);
            streamInfos.put(streamInfo.getDeviceID() + "_" + streamInfo.getChannelId(), streamInfo);
        }
        return streamInfos;
    }


    @Override
    public boolean startPlayback(StreamInfo stream) {
        return redis.set(String.format("%S_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX, userSetup.getServerId(),stream.getStreamId(),
                stream.getDeviceID(), stream.getChannelId()), stream);
    }

    @Override
    public boolean startDownload(StreamInfo streamInfo) {
        return redis.set(String.format("%S_%s_%s_%s_%s", VideoManagerConstants.DOWNLOAD_PREFIX, userSetup.getServerId(), streamInfo.getStreamId(),
                streamInfo.getDeviceID(), streamInfo.getChannelId()), streamInfo);
    }

    @Override
    public boolean stopPlayback(StreamInfo streamInfo) {
        if (streamInfo == null) return false;
        DeviceChannel deviceChannel = deviceChannelMapper.queryChannel(streamInfo.getDeviceID(), streamInfo.getChannelId());
        if (deviceChannel != null) {
            deviceChannel.setStreamId(null);
            deviceChannel.setDeviceId(streamInfo.getDeviceID());
            deviceChannelMapper.update(deviceChannel);
        }
        return redis.del(String.format("%S_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetup.getServerId(),
                streamInfo.getStreamId(),
                streamInfo.getDeviceID(),
                streamInfo.getChannelId()));
    }

    @Override
    public StreamInfo queryPlaybackByDevice(String deviceId, String code) {
        // String format = String.format("%S_*_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
        //         deviceId,
        //         code);
        List<Object> playLeys = redis.scan(String.format("%S_%s_*_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetup.getServerId(),
                deviceId,
                code));
        if (playLeys == null || playLeys.size() == 0) {
            playLeys = redis.scan(String.format("%S_%s_*_*_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                    userSetup.getServerId(),
                    deviceId));
        }
        if (playLeys == null || playLeys.size() == 0) return null;
        return (StreamInfo) redis.get(playLeys.get(0).toString());
    }

    @Override
    public void updatePlatformCatchInfo(ParentPlatformCatch parentPlatformCatch) {
        String key = VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetup.getServerId() + "_" + parentPlatformCatch.getId();
        redis.set(key, parentPlatformCatch);
    }

    @Override
    public void updatePlatformKeepalive(ParentPlatform parentPlatform) {
        String key = VideoManagerConstants.PLATFORM_KEEPALIVE_PREFIX + userSetup.getServerId() + "_" + parentPlatform.getServerGBId();
        redis.set(key, "", Integer.parseInt(parentPlatform.getKeepTimeout()));
    }

    @Override
    public void updatePlatformRegister(ParentPlatform parentPlatform) {
        String key = VideoManagerConstants.PLATFORM_REGISTER_PREFIX + userSetup.getServerId() + "_" + parentPlatform.getServerGBId();
        redis.set(key, "", Integer.parseInt(parentPlatform.getExpires()));
    }

    @Override
    public ParentPlatformCatch queryPlatformCatchInfo(String platformGbId) {
        return (ParentPlatformCatch) redis.get(VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetup.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformCatchInfo(String platformGbId) {
        redis.del(VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetup.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformKeepalive(String platformGbId) {
        redis.del(VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetup.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformRegister(String platformGbId) {
        redis.del(VideoManagerConstants.PLATFORM_REGISTER_PREFIX + userSetup.getServerId() + "_" + platformGbId);
    }


    @Override
    public void updatePlatformRegisterInfo(String callId, String platformGbId) {
        String key = VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetup.getServerId() + "_" + callId;
        redis.set(key, platformGbId);
    }


    @Override
    public String queryPlatformRegisterInfo(String callId) {
        return (String) redis.get(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetup.getServerId() + "_" + callId);
    }

    @Override
    public void delPlatformRegisterInfo(String callId) {
        redis.del(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetup.getServerId() + "_" + callId);
    }

    @Override
    public void cleanPlatformRegisterInfos() {
        List regInfos = redis.scan(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetup.getServerId() + "_" + "*");
        for (Object key : regInfos) {
            redis.del(key.toString());
        }
    }

    @Override
    public void updateSendRTPSever(SendRtpItem sendRtpItem) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX + userSetup.getServerId() + "_" + sendRtpItem.getPlatformId() + "_" + sendRtpItem.getChannelId();
        redis.set(key, sendRtpItem);
    }

    @Override
    public SendRtpItem querySendRTPServer(String platformGbId, String channelId) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX + userSetup.getServerId() + "_" + platformGbId + "_" + channelId;
        return (SendRtpItem) redis.get(key);
    }

    @Override
    public List<SendRtpItem> querySendRTPServer(String platformGbId) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX + userSetup.getServerId() + "_" + platformGbId + "_*";
        List<Object> queryResult = redis.scan(key);
        List<SendRtpItem> result = new ArrayList<>();

        for (int i = 0; i < queryResult.size(); i++) {
            String keyItem = (String) queryResult.get(i);
            result.add((SendRtpItem) redis.get(keyItem));
        }

        return result;
    }

    /**
     * 删除RTP推送信息缓存
     *
     * @param platformGbId
     * @param channelId
     */
    @Override
    public void deleteSendRTPServer(String platformGbId, String channelId) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX + userSetup.getServerId() + "_" + platformGbId + "_" + channelId;
        redis.del(key);
    }

    /**
     * 查询某个通道是否存在上级点播（RTP推送）
     *
     * @param channelId
     */
    @Override
    public boolean isChannelSendingRTP(String channelId) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX + userSetup.getServerId() + "_" + "*_" + channelId;
        List<Object> RtpStreams = redis.scan(key);
        if (RtpStreams.size() > 0) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void clearCatchByDeviceId(String deviceId) {
        List<Object> playLeys = redis.scan(String.format("%S_%s_*_%s_*", VideoManagerConstants.PLAYER_PREFIX,
                userSetup.getServerId(),
                deviceId));
        if (playLeys.size() > 0) {
            for (Object key : playLeys) {
                redis.del(key.toString());
            }
        }

        List<Object> playBackers = redis.scan(String.format("%S_%s_*_%s_*", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetup.getServerId(),
                deviceId));
        if (playBackers.size() > 0) {
            for (Object key : playBackers) {
                redis.del(key.toString());
            }
        }
    }

    @Override
    public void outlineForAll() {
        List<Object> onlineDevices = redis.scan(VideoManagerConstants.KEEPLIVEKEY_PREFIX + userSetup.getServerId() + "_" + "*");
        for (int i = 0; i < onlineDevices.size(); i++) {
            String key = (String) onlineDevices.get(i);
            redis.del(key);
        }
    }

    @Override
    public List<String> getOnlineForAll() {
        List<String> result = new ArrayList<>();
        List<Object> onlineDevices = redis.scan(VideoManagerConstants.KEEPLIVEKEY_PREFIX + userSetup.getServerId() + "_" + "*");
        for (int i = 0; i < onlineDevices.size(); i++) {
            String key = (String) onlineDevices.get(i);
            result.add((String) redis.get(key));
        }
        return result;
    }

    @Override
    public void updateWVPInfo(JSONObject jsonObject, int time) {
        String key = VideoManagerConstants.WVP_SERVER_PREFIX + userSetup.getServerId();
        redis.set(key, jsonObject, time);
    }

    @Override
    public void sendStreamChangeMsg(String type, JSONObject jsonObject) {
        String key = VideoManagerConstants.WVP_MSG_STREAM_CHANGE__PREFIX + type;
        logger.debug("[redis 流变化事件] {}: {}", key, jsonObject.toString());
        redis.convertAndSend(key, jsonObject);
    }

    @Override
    public void addStream(MediaServerItem mediaServerItem, String type, String app, String streamId, StreamInfo streamInfo) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PUSH_PREFIX + userSetup.getServerId() + "_" + type + "_" + app + "_" + streamId + "_" + mediaServerItem.getId();
        redis.set(key, streamInfo);
    }

    @Override
    public void removeStream(MediaServerItem mediaServerItem, String type, String app, String streamId) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PUSH_PREFIX + userSetup.getServerId() + "_*_" + app + "_" + streamId + "_" + mediaServerItem.getId();
        List<Object> streams = redis.scan(key);
        for (Object stream : streams) {
            redis.del((String) stream);
        }
    }

    @Override
    public StreamInfo queryDownloadByStreamId(String streamId) {
        List<Object> playLeys = redis.scan(String.format("%S_%s_%s_*", VideoManagerConstants.DOWNLOAD_PREFIX, userSetup.getServerId(), streamId));
        if (playLeys == null || playLeys.size() == 0) return null;
        return (StreamInfo) redis.get(playLeys.get(0).toString());
    }
}
