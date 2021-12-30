package com.genersoft.iot.vmp.media.zlm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.genersoft.iot.vmp.conf.BusConfig;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.ConnectException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class ZLMRESTfulUtils {

    private final static Logger logger = LoggerFactory.getLogger(ZLMRESTfulUtils.class);
    @Autowired
    private Environment env;
    @Autowired
    BusConfig busConfig;

    public interface RequestCallback {
        void run(JSONObject response);
    }
    public interface UploadCallback {
        void run(JSONObject response, String msg);
    }
    public JSONObject sendPost(MediaServerItem mediaServerItem, String api, Map<String, Object> param, RequestCallback callback) {
        OkHttpClient client = new OkHttpClient();
        String url = String.format("http://%s:%s/index/api/%s", mediaServerItem.getIp(), mediaServerItem.getHttpPort(), api);
        JSONObject responseJSON = null;

        FormBody.Builder builder = new FormBody.Builder();
        builder.add("secret", mediaServerItem.getSecret());
        if (param != null && param.keySet().size() > 0) {
            for (String key : param.keySet()) {
                if (param.get(key) != null) {
                    builder.add(key, param.get(key).toString());
                }
            }
        }

        FormBody body = builder.build();

        Request request = new Request.Builder()
                .post(body)
                .url(url)
                .build();
        if (callback == null) {
            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    ResponseBody responseBody = response.body();
                    if (responseBody != null) {
                        String responseStr = responseBody.string();
                        responseJSON = JSON.parseObject(responseStr);
                    }
                } else {
                    response.close();
                    Objects.requireNonNull(response.body()).close();
                }
            } catch (ConnectException e) {
                logger.error(String.format("连接ZLM失败: %s, %s", e.getCause().getMessage(), e.getMessage()));
                logger.info("请检查media配置并确认ZLM已启动...");
            } catch (IOException e) {
                logger.error(String.format("[ %s ]请求失败: %s", url, e.getMessage()));
            }
        } else {
            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onResponse(@NotNull Call call, @NotNull Response response) {
                    if (response.isSuccessful()) {
                        try {
                            String responseStr = Objects.requireNonNull(response.body()).string();
                            callback.run(JSON.parseObject(responseStr));
                        } catch (IOException e) {
                            logger.error(String.format("[ %s ]请求失败: %s", url, e.getMessage()));
                        }

                    } else {
                        response.close();
                        Objects.requireNonNull(response.body()).close();
                    }
                }

                @Override
                public void onFailure(@NotNull Call call, @NotNull IOException e) {
                    logger.error(String.format("连接ZLM失败: %s, %s", e.getCause().getMessage(), e.getMessage()));
                    logger.info("请检查media配置并确认ZLM已启动...");
                }
            });
        }


        return responseJSON;
    }

    public void sendGetForImg(MediaServerItem mediaServerItem, String api, Map<String, Object> params, String targetPath, String fileName) {
        String url = String.format("http://%s:%s/index/api/%s", mediaServerItem.getIp(), mediaServerItem.getHttpPort(), api);
        logger.debug(url);
        HttpUrl parseUrl = HttpUrl.parse(url);
        if (parseUrl == null) {
            return;
        }
        HttpUrl.Builder httpBuilder = parseUrl.newBuilder();

        httpBuilder.addQueryParameter("secret", mediaServerItem.getSecret());
        if (params != null) {
            for (Map.Entry<String, Object> param : params.entrySet()) {
                httpBuilder.addQueryParameter(param.getKey(), param.getValue().toString());
            }
        }

        Request request = new Request.Builder()
                .url(httpBuilder.build())
                .build();
        logger.info(request.toString());
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                logger.info("response body contentType: " + Objects.requireNonNull(response.body()).contentType());
                if (targetPath != null) {
                    File snapFolder = new File(targetPath);
                    if (!snapFolder.exists()) {
                        if (!snapFolder.mkdirs()) {
                            logger.warn("{}路径创建失败", snapFolder.getAbsolutePath());
                        }

                    }
                    File snapFile = new File(targetPath + "/" + fileName);
                    FileOutputStream outStream = new FileOutputStream(snapFile);

                    outStream.write(Objects.requireNonNull(response.body()).bytes());
                    outStream.close();
                } else {
                    logger.error(String.format("[ %s ]请求失败: %s %s", url, response.code(), response.message()));
                }
                Objects.requireNonNull(response.body()).close();
            } else {
                logger.error(String.format("[ %s ]请求失败: %s %s", url, response.code(), response.message()));
            }
        } catch (ConnectException e) {
            logger.error(String.format("连接ZLM失败: %s, %s", e.getCause().getMessage(), e.getMessage()));
            logger.info("请检查media配置并确认ZLM已启动...");
        } catch (IOException e) {
            logger.error(String.format("[ %s ]请求失败: %s", url, e.getMessage()));
        }
    }

    public JSONObject getMediaList(MediaServerItem mediaServerItem, String app, String stream, String schema, RequestCallback callback) {
        Map<String, Object> param = new HashMap<>();
        if (app != null) {
            param.put("app", app);
        }
        if (stream != null) {
            param.put("stream", stream);
        }
        if (schema != null) {
            param.put("schema", schema);
        }
        param.put("vhost", "__defaultVhost__");
        return sendPost(mediaServerItem, "getMediaList", param, callback);
    }

    public JSONObject getMediaList(MediaServerItem mediaServerItem, String app, String stream) {
        return getMediaList(mediaServerItem, app, stream, null, null);
    }

    public JSONObject getMediaList(MediaServerItem mediaServerItem, RequestCallback callback) {
        return sendPost(mediaServerItem, "getMediaList", null, callback);
    }

    public JSONObject getMediaInfo(MediaServerItem mediaServerItem, String app, String schema, String stream) {
        Map<String, Object> param = new HashMap<>();
        param.put("app", app);
        param.put("schema", schema);
        param.put("stream", stream);
        param.put("vhost", "__defaultVhost__");
        return sendPost(mediaServerItem, "getMediaInfo", param, null);
    }

    public JSONObject getRtpInfo(MediaServerItem mediaServerItem, String stream_id) {
        Map<String, Object> param = new HashMap<>();
        param.put("stream_id", stream_id);
        return sendPost(mediaServerItem, "getRtpInfo", param, null);
    }

    public JSONObject addFFmpegSource(MediaServerItem mediaServerItem, String src_url, String dst_url, String timeout_ms,
                                      boolean enable_hls, boolean enable_mp4, String ffmpeg_cmd_key) {
        logger.info(src_url);
        logger.info(dst_url);
        Map<String, Object> param = new HashMap<>();
        param.put("src_url", src_url);
        param.put("dst_url", dst_url);
        param.put("timeout_ms", timeout_ms);
        param.put("enable_hls", enable_hls);
        param.put("enable_mp4", enable_mp4);
        param.put("ffmpeg_cmd_key", ffmpeg_cmd_key);
        return sendPost(mediaServerItem, "addFFmpegSource", param, null);
    }

    public JSONObject delFFmpegSource(MediaServerItem mediaServerItem, String key) {
        Map<String, Object> param = new HashMap<>();
        param.put("key", key);
        return sendPost(mediaServerItem, "delFFmpegSource", param, null);
    }

    public JSONObject getMediaServerConfig(MediaServerItem mediaServerItem) {
        return sendPost(mediaServerItem, "getServerConfig", null, null);
    }

    public JSONObject setServerConfig(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "setServerConfig", param, null);
    }

    public JSONObject openRtpServer(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "openRtpServer", param, null);
    }

    public JSONObject notifyFileDownladComplete(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "notifyFileDownladComplete", param, null);
    }

    public JSONObject closeRtpServer(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "closeRtpServer", param, null);
    }

    public JSONObject listRtpServer(MediaServerItem mediaServerItem) {
        return sendPost(mediaServerItem, "listRtpServer", null, null);
    }

    public JSONObject startSendRtp(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "startSendRtp", param, null);
    }

    public JSONObject stopSendRtp(MediaServerItem mediaServerItem, Map<String, Object> param) {
        return sendPost(mediaServerItem, "stopSendRtp", param, null);
    }

    public JSONObject addStreamProxy(MediaServerItem mediaServerItem, String app, String stream, String url, boolean enable_hls, boolean enable_mp4, String rtp_type) {
        Map<String, Object> param = new HashMap<>();
        param.put("vhost", "__defaultVhost__");
        param.put("app", app);
        param.put("stream", stream);
        param.put("url", url);
        param.put("enable_hls", enable_hls ? 1 : 0);
        param.put("enable_mp4", enable_mp4 ? 1 : 0);
        param.put("rtp_type", rtp_type);
        return sendPost(mediaServerItem, "addStreamProxy", param, null);
    }

    public JSONObject closeStreams(MediaServerItem mediaServerItem, String app, String stream) {
        Map<String, Object> param = new HashMap<>();
        param.put("vhost", "__defaultVhost__");
        param.put("app", app);
        param.put("stream", stream);
        param.put("force", 1);
        return sendPost(mediaServerItem, "close_streams", param, null);
    }

    public JSONObject getAllSession(MediaServerItem mediaServerItem) {
        return sendPost(mediaServerItem, "getAllSession", null, null);
    }

    public void kickSessions(MediaServerItem mediaServerItem, String localPortSStr) {
        Map<String, Object> param = new HashMap<>();
        param.put("local_port", localPortSStr);
        sendPost(mediaServerItem, "kick_sessions", param, null);
    }

    public void getSnap(MediaServerItem mediaServerItem, String flvUrl, int timeout_sec, int expire_sec, String targetPath, String fileName) {
        Map<String, Object> param = new HashMap<>();
        param.put("url", flvUrl);
        param.put("timeout_sec", timeout_sec);
        param.put("expire_sec", expire_sec);
        sendGetForImg(mediaServerItem, "getSnap", param, targetPath, fileName);
    }

    public void downloadFile(MediaServerItem mediaServerItem,String deviceId,int channel,String start,String end,RequestCallback callback){

        Map<String, Object> param = new HashMap<>();
        param.put("deviceId", deviceId);
        param.put("channel", channel);
        param.put("start", start);
        param.put("end", end);
        param.put("ip", env.getProperty("devices."+deviceId+".ip"));
        param.put("user", env.getProperty("devices."+deviceId+".user"));
        param.put("pwd", env.getProperty("devices."+deviceId+".pwd"));
        String property = env.getProperty("devices." + deviceId + ".http-port");
        param.put("port", env.getProperty("devices."+deviceId+".http-port"));
        sendPost(mediaServerItem, "hk_download", param, callback);
    }
    public void uploadFile(String fileName,UploadCallback callback) {
        logger.info(String.format("开始上传文件: %s, %s", busConfig.getUploadurl(), fileName));
        File file = new File(fileName);

        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "file",
                        RequestBody.create(MediaType.parse("multipart/form-data"), file))
                .build();
        Request request = new Request.Builder()
                .post(requestBody)
                .url(busConfig.getUploadurl())
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                if (response.isSuccessful()) {
                    try {
                        String responseStr = Objects.requireNonNull(response.body()).string();
                        callback.run(JSON.parseObject(responseStr),"success");
                    } catch (IOException e) {
                        logger.error(String.format("[ %s ]上传文件失败: %s", busConfig.getUploadurl(), e.getMessage()));
                        callback.run(null,e.getMessage());
                    }

                } else {
                    response.close();
                    Objects.requireNonNull(response.body()).close();
                    callback.run(null,"上传文件失败");
                }
            }

            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                logger.error(String.format("上传文件失败: %s,%s", fileName,e==null?"": e.getMessage()));
                e.printStackTrace();
                callback.run(null,"上传文件失败："+ e==null?"":e.getMessage());
            }
        });
    }
}
