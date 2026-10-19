package com.hui.huiaicodemother.service;

import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {
    /**
     * 下载项目
     */
    public void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);
}
