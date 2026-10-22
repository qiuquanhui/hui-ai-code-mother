package com.hui.huiaicodemother.langgraph4j.tools;

import cn.hutool.core.util.StrUtil;
import java.net.URLEncoder;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hui.huiaicodemother.langgraph4j.model.ImageResource;
import com.hui.huiaicodemother.langgraph4j.model.enums.ImageCategoryEnum;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片收集工具（插画图片）
 */
@Slf4j
@Component
public class UndrawIllustrationTool {

    @Value("${undraw.search-url:https://undraw.co/_next/data/mMWmJSt23qpgo8cLTD_pB/search/%s.json?term=%s}")
    private String searchUrl = "https://undraw.co/_next/data/mMWmJSt23qpgo8cLTD_pB/search/%s.json?term=%s";

    @Tool("搜索插画图片，用于网站美化和装饰")
    public List<ImageResource> searchIllustrations(@P("搜索关键词") String query) {
        List<ImageResource> imageList = new ArrayList<>();
        int searchCount = 12;
        if (StrUtil.isBlank(query)) return imageList;
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        String apiUrl = String.format(searchUrl, encodedQuery, encodedQuery);

        // 使用 try-with-resources 自动释放 HTTP 资源
        try (HttpResponse response = HttpRequest.get(apiUrl).timeout(10000).execute()) {
            if (!response.isOk()) {
                return imageList;
            }
            JSONObject result = JSONUtil.parseObj(response.body());
            JSONObject pageProps = result.getJSONObject("pageProps");
            if (pageProps == null) {
                return imageList;
            }
            JSONArray initialResults = pageProps.getJSONArray("initialResults");
            if (initialResults == null || initialResults.isEmpty()) {
                return imageList;
            }
            int actualCount = Math.min(searchCount, initialResults.size());
            for (int i = 0; i < actualCount; i++) {
                JSONObject illustration = initialResults.getJSONObject(i);
                String title = illustration.getStr("title", "插画");
                String media = illustration.getStr("media", "");
                if (StrUtil.isNotBlank(media)) {
                    imageList.add(ImageResource.builder()
                            .category(ImageCategoryEnum.ILLUSTRATION)
                            .description(title)
                            .url(media)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("搜索插画失败：{}", e.getMessage(), e);
        }
        return imageList;
    }
}
