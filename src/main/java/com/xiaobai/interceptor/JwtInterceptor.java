package com.xiaobai.interceptor;

import com.alibaba.fastjson.JSONObject;
import com.xiaobai.common.base.CommonRuntimeException;
import com.xiaobai.common.base.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.ui.ModelMap;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.WebRequestInterceptor;

import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT认证拦截器
 *
 * @author bail
 * @date 2018/12/31.9:17
 */
@Slf4j
public class JwtInterceptor implements WebRequestInterceptor {

    private static final String DEFAULT_JWT_HEADER_KEY = "Authorization";

    private static final String DEFAULT_JWT_HEADER_START = "Bearer ";

    private static final String ERROR_SERVLET_PATH = "/error";

    private String jwtSecretKey;

    /**
     * 获取jwt信息的key
     */
    private String jwtHeaderKey;

    /**
     * jwt信息前缀
     */
    private String jwtHeaderPrefix;

    /**
     * 排除的restful url Map；被排除的url将不参与jwt验证；
     */
    private JSONObject excludedRestUrlMap;

    /**
     * 路径比较器
     */
    private static AntPathMatcher antPathMatcher = new AntPathMatcher();

    public JwtInterceptor(String jwtSecretKey, String jwtHeaderKey, String jwtHeaderPrefix, String excludedRestUrls) {
        this.jwtSecretKey = jwtSecretKey;
        this.jwtHeaderKey = StringUtils.isNotEmpty(jwtHeaderKey) ? jwtHeaderKey : DEFAULT_JWT_HEADER_KEY;
        this.jwtHeaderPrefix = StringUtils.isNotEmpty(jwtHeaderPrefix) ? jwtHeaderPrefix : DEFAULT_JWT_HEADER_START;
        this.excludedRestUrlMap = JSONObject.parseObject(excludedRestUrls);
    }

    @Override
    public void preHandle(WebRequest request) {
        if (request instanceof ServletWebRequest) {
            ServletWebRequest webRequest = (ServletWebRequest) request;
            String servletPath = webRequest.getRequest().getServletPath();
            HttpMethod httpMethod = webRequest.getHttpMethod();
            log.debug("JwtInterceptor preHandle in ..., servletPath:{}", servletPath);

            if (HttpMethod.OPTIONS.equals(webRequest.getHttpMethod()) || ERROR_SERVLET_PATH.equals(servletPath)) {
                return; // ignore options request and error path
            }

            // 不拦截excludedRestUrl中定义的部分GET,POST..请求
            if (excludedRestUrlMap != null && !excludedRestUrlMap.isEmpty()) {
                List<String> list = excludedRestUrlMap.keySet().stream().filter(excludedRestUrl -> antPathMatcher.match
                        (excludedRestUrl, servletPath)).collect(Collectors.toList());
                if (list != null && !list.isEmpty()) {
                    for (String restUrl : list) {
                        String reqMethodStr = excludedRestUrlMap.getString(restUrl);
                        if (reqMethodStr != null) {
                            if (reqMethodStr.contains(httpMethod.name())) {
                                return;
                            }
                        }
                    }
                }
            }

            final String authHeader = request.getHeader(this.jwtHeaderKey);
            String token;
            // 1.优先从header中截取"Bearer "后的token
            if (authHeader != null && authHeader.startsWith(this.jwtHeaderPrefix)) {
                token = authHeader.substring(7);
            } else {
                // 2.再从请求路径中获取token
                token = parseExportUrlToken(servletPath);
                if (token == null) {
                    log.info("JwtInterceptor preHandle out ['{} Missing jwtToken']", servletPath);
                    throw new CommonRuntimeException(ErrorCode.MISSING_TOKEN);
                }
            }

            try {
                final Claims claims = Jwts.parser().setSigningKey(jwtSecretKey).parseClaimsJws(token).getBody();
                webRequest.setAttribute("claims", claims, RequestAttributes.SCOPE_REQUEST);
                log.debug("JwtInterceptor preHandle out ['Authorization success']");
            } catch (ExpiredJwtException e) {
                log.info("JwtInterceptor preHandle out ['{} Expired jwtToken'], exMsg:{}", servletPath, e.getMessage());
                throw new CommonRuntimeException(ErrorCode.EXPIRED_TOKEN);
            } catch (Exception e) {
                log.info("JwtInterceptor preHandle out ['{} Invalid jwtToken'], exMsg:{}", servletPath, e.getMessage());
                throw new CommonRuntimeException(ErrorCode.INVALID_TOKEN);
            }
        }

    }

    /**
     * 从请求路径中获取token
     *
     * @param exportUrl 导出文件的url路径(形如:/system/exportLogByParams/{encrypted})
     * @return token
     */
    private String parseExportUrlToken(String exportUrl) {
        try {
            String[] paths = exportUrl.split("/");
            String encrypted = paths[paths.length - 1];
            String decrypted = new String(Base64.getUrlDecoder().decode(encrypted.getBytes()));
            JSONObject tokenObj = JSONObject.parseObject(decrypted);
            return tokenObj.getString("token");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void postHandle(WebRequest request, ModelMap model) throws Exception {

    }

    @Override
    public void afterCompletion(WebRequest request, Exception ex) throws Exception {

    }
}
