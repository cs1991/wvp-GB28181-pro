package com.genersoft.iot.vmp.conf;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.context.annotation.Configuration;

/**
 * @description:业务信息配置类，
 * @author: swwheihei
 * @date: 2019年5月30日 上午10:58:25
 * 
 */
@Configuration
public class BusConfig {

	@Value("${bus.uploadurl}")
	private String uploadurl;

	@Value("${bus.enablehk}")
	private boolean enablehk;

	public boolean isEnablehk() {
		return enablehk;
	}

	public void setEnablehk(boolean enablehk) {
		this.enablehk = enablehk;
	}

	public String getUploadurl() {
		return uploadurl;
	}

	public void setUploadurl(String uploadurl) {
		this.uploadurl = uploadurl;
	}
}
