package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.SendRtpItem;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.media.zlm.ZLMRTPServerFactory;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.service.IMediaServerService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorager;
import org.apache.http.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.*;
import javax.sip.address.SipURI;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderAddress;
import javax.sip.header.ToHeader;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import static sun.audio.AudioDevice.device;

/**
 * SIP命令类型： BYE请求
 */
@Component
public class ByeRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {

	private final Logger logger = LoggerFactory.getLogger(ByeRequestProcessor.class);
	private final String method = "BYE";

	@Autowired
	private ISIPCommander cmder;

	@Autowired
	private IRedisCatchStorage redisCatchStorage;

	@Autowired
	private IVideoManagerStorager storager;

	@Autowired
	private ZLMRTPServerFactory zlmrtpServerFactory;

	@Autowired
	private IMediaServerService mediaServerService;

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addRequestProcessor(method, this);
	}

	/**
	 * 处理BYE请求
	 * @param evt
	 */
	@Override
	public void process(RequestEvent evt) {
		try {
			responseAck(evt, Response.OK);
			Dialog dialog = evt.getDialog();
			if (dialog == null) return;
			if (dialog.getState().equals(DialogState.TERMINATED)) {
				//这里是作为gb28181客户端向上级rtp推流
				String platformGbId = ((SipURI) ((HeaderAddress) evt.getRequest().getHeader(FromHeader.NAME)).getAddress().getURI()).getUser();
				String channelId = ((SipURI) ((HeaderAddress) evt.getRequest().getHeader(ToHeader.NAME)).getAddress().getURI()).getUser();
				SendRtpItem sendRtpItem =  redisCatchStorage.querySendRTPServer(platformGbId, channelId);
				logger.info("收到bye, [{}/{}]", platformGbId, channelId);
				if (sendRtpItem != null){
					String streamId = sendRtpItem.getStreamId();
					Map<String, Object> param = new HashMap<>();
					param.put("vhost","__defaultVhost__");
					param.put("app",sendRtpItem.getApp());
					param.put("stream",streamId);
					param.put("ssrc",sendRtpItem.getSsrc());
					logger.info("停止向上级推流：" + streamId);
					MediaServerItem mediaInfo = mediaServerService.getOne(sendRtpItem.getMediaServerId());
					zlmrtpServerFactory.stopSendRtpStream(mediaInfo, param);
					redisCatchStorage.deleteSendRTPServer(platformGbId, channelId);
					if (zlmrtpServerFactory.totalReaderCount(mediaInfo, sendRtpItem.getApp(), streamId) == 0) {
						logger.info(streamId + "无其它观看者，通知设备停止推流");
						cmder.streamByeCmd(VideoManagerConstants.VIDEO_PREVIEW,sendRtpItem.getDeviceId(), channelId);
					}
				}
				//先判断是不是下载文件的，
				String callId = evt.getDialog().getDialogId();
				callId = callId.substring(0,callId.indexOf("@"));
				String streamId = redisCatchStorage.queryDownload(callId);
				if(!TextUtils.isEmpty(streamId)){
					// 可能是设备主动停止
					Device device = storager.queryVideoDeviceByChannelId(platformGbId);
					//是下载文件的
					mediaServerService.notifyFileDownladComplete(streamId,device,platformGbId,VideoManagerConstants.VIDEO_DOWNLOAD);
					redisCatchStorage.stopDownloadFile(callId);
				}else{
					// 可能是设备主动停止
					Device device = storager.queryVideoDeviceByChannelId(platformGbId);
					if (device != null) {
						StreamInfo streamInfo = redisCatchStorage.queryPlayByDevice(device.getDeviceId(), platformGbId);
						if (streamInfo != null) {
							redisCatchStorage.stopPlay(streamInfo);
						}
						storager.stopPlay(device.getDeviceId(), platformGbId);
						mediaServerService.closeRTPServer(device, platformGbId,VideoManagerConstants.VIDEO_PREVIEW);
					}
				}

			}
		} catch (SipException e) {
			e.printStackTrace();
		} catch (InvalidArgumentException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}
}
