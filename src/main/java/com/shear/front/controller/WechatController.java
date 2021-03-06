package com.shear.front.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang.StringUtils;
import org.jdom.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.quickshear.common.wechat.pay.util.HttpClientUtil;
import com.quickshear.common.wechat.pay.util.Sha1Util;
import com.quickshear.common.wechat.pay.util.XMLUtil;

/**
 * <code>https://mp.weixin.qq.com/wiki/17/c0f37d5704f0b64713d5d2c37b468d75.html</code>
 * 
 * REDIRECT_URI设置为当前地址:shear/callback
 * 
 */
@Controller
@RequestMapping("/open")
public class WechatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WechatController.class);
    
    @RequestMapping(value="/token", produces = "application/json; charset=utf-8")
    @ResponseBody
    public String token(Model model, HttpServletRequest request) {
	LOGGER.info("enter token...");
	String signature = request.getParameter("signature");
	String timestamp = request.getParameter("timestamp");
	String nonce = request.getParameter("nonce");
	String echostr = request.getParameter("echostr");
	String openid = null;
	if (checkSignature("qsstoken", signature, timestamp, nonce)) {
	    Map<String, String> map = new HashMap<String, String>();
	    InputStream in;
	   
	    try {
		in = request.getInputStream();
		if (in != null) {
		    String res = HttpClientUtil.InputStreamTOString(in, "utf-8");
		    LOGGER.info("res:" + res);
		    if (StringUtils.isNotBlank(res)) {
			map = XMLUtil.doXMLParse(res);
			LOGGER.info("map:" + map);
			if (null != map) {
			    openid = map.get("FromUserName");
			    
			    LOGGER.info("watch()  cookie :{}", openid);
			    String event = map.get("Event");
				if("subscribe".equals(event)){
					String content="<xml><ToUserName><![CDATA[%s]]></ToUserName><FromUserName><![CDATA[%s]]></FromUserName><CreateTime>%s</CreateTime><MsgType><![CDATA[%s]]></MsgType><Content><![CDATA[%s]]></Content><FuncFlag>0</FuncFlag></xml>";
					String my = map.get("ToUserName");
					String r = String.format(content,openid,my,timestamp,"text","为生活做剪发，让你成为最好的你。现在点击\"我要剪发\"，查看离您最近的仟丝顺门店。");
					return r;
				}
			    String msgId = map.get("MsgId");
			    if(StringUtils.isNotBlank(msgId)){
				String content="<xml><ToUserName><![CDATA[%s]]></ToUserName><FromUserName><![CDATA[%s]]></FromUserName><CreateTime>%s</CreateTime><MsgType><![CDATA[%s]]></MsgType><Content><![CDATA[%s]]></Content><FuncFlag>0</FuncFlag></xml>";
				String my = map.get("ToUserName");
				String r = String.format(content,openid,my,timestamp,"text","您好，仟丝顺稍候回复您消息。");
				return r;
			    }
			   
			}
		    }
		}
	    } catch (IOException e) {
		e.printStackTrace();
		LOGGER.error("watch()", e);
	    } catch (JDOMException e) {
		LOGGER.error("watch()", e);
		e.printStackTrace();
	    }
	}
	return echostr;
    }
    
    public static boolean checkSignature(String token, String signature, String timestamp, String nonce) {
	String[] arr = new String[] { token, timestamp, nonce };
	// sort
	Arrays.sort(arr);

	// generate String
	String content = arr[0] + arr[1] + arr[2];

	String temp = Sha1Util.getSha1(content);
	return temp.equalsIgnoreCase(signature);
    }
    
}
