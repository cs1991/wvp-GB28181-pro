package com.genersoft.iot.vmp.service;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.ZLMServerConfig;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;

import java.util.List;

/**
 * 媒体服务节点
 */
public interface IMediaServerService {

    List<MediaServerItem> getAll();

    List<MediaServerItem> getAllFromDatabase();

    List<MediaServerItem> getAllOnline();

    MediaServerItem getOne(String generalMediaServerId);

    MediaServerItem getOneByHostAndPort(String host, int port);

    /**
     * 新的节点加入
     * @param zlmServerConfig
     * @return
     */
    void handLeZLMServerConfig(ZLMServerConfig zlmServerConfig);

    MediaServerItem getMediaServerForMinimumLoad();

    void setZLMConfig(MediaServerItem mediaServerItem);

    void downloadBackFile(MediaServerItem mediaServerItem, String deviceId, int channel, String start, String end, ZLMRESTfulUtils.UploadCallback callback);
    SSRCInfo openRTPServer(MediaServerItem mediaServerItem, String streamId);

    SSRCInfo openRTPServer(MediaServerItem mediaServerItem, String streamId,boolean isPlayback, String callId);

    void closeRTPServer(Device device, String channelId, String videoType);

    void notifyFileDownladComplete(String stream_id,Device device, String channelId, String videoType);

    void clearRTPServer(MediaServerItem mediaServerItem);

    void update(MediaServerItem mediaSerItem);

    void addCount(String mediaServerId);

    void removeCount(String mediaServerId);

    void releaseSsrc(MediaServerItem mediaServerItem, String ssrc);

    void clearMediaServerForOnline();

    WVPResult<String> add(MediaServerItem mediaSerItem);
    int addToDatabase(MediaServerItem mediaSerItem);
    void resetOnlineServerItem(MediaServerItem serverItem);

    WVPResult<MediaServerItem> checkMediaServer(String ip, int port, String secret);

    boolean checkMediaRecordServer(String ip, int port);

    void delete(String id);

    MediaServerItem getDefaultMediaServer();
}
