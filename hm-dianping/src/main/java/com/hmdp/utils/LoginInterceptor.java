package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author kaede
 * @create 2023-01-23
 */

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截
        if (UserHolder.getUser() == null) {
            // 拦截
            response.setStatus(401);
            return false;
        }
        // 2.有用户则放行
        return true;
    }

}
