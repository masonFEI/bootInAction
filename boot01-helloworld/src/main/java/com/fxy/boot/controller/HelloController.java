/**
 * LY.com Inc.
 * Copyright (c) 2004-2026 All Rights Reserved.
 */
package com.fxy.boot.controller;

import com.fxy.boot.model.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * HelloController
 *
 * @author feixuanyu
 * @version 1.0.0
 * @since 2026-11-18 14:01
 */
@Controller
public class HelloController {

    @Value("${user.name}")
    private String userName;

    @ResponseBody
    @RequestMapping("/hello")
    public String handle01() {
        return "hello,spring boot 2,中文";
    }

    @ResponseBody
    @PostMapping("/hello2")
    public Response handle02() {

        Response response = new Response();

        response.setMessage(userName);

        response.setCode(200);

        return response;
    }

}
