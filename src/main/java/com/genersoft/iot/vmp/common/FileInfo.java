package com.genersoft.iot.vmp.common;

public class FileInfo {
    String fileName;
    String bucketName;
    String url;

    public FileInfo(String fileName, String bucketName, String url) {
        this.fileName = fileName;
        this.bucketName = bucketName;
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
