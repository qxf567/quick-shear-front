package com.shear.front.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cglib.beans.BeanCopier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.aliyuncs.exceptions.ClientException;
import com.quickshear.common.lru.LRUCache;
import com.quickshear.common.util.DateUtil;
import com.quickshear.common.wechat.WechatManager;
import com.quickshear.common.wechat.WechatManagerNew;
import com.quickshear.common.wechat.WechatTemplateMsgSender;
import com.quickshear.common.wechat.domain.WechatTemplateOrderStatusMsg;
import com.quickshear.common.wechat.pay.AccessTokenUtil;
import com.quickshear.common.wechat.pay.RequestHandler;
import com.quickshear.common.wechat.pay.ResponseHandler;
import com.quickshear.common.wechat.pay.TenpayConfig;
import com.quickshear.common.wechat.pay.util.Sha1Util;
import com.quickshear.common.wechat.pay.util.XMLUtil;
import com.quickshear.domain.Customer;
import com.quickshear.domain.Order;
import com.quickshear.domain.Shop;
import com.quickshear.domain.query.CustomerQuery;
import com.quickshear.domain.query.OrderQuery;
import com.quickshear.service.CustomerService;
import com.quickshear.service.HairstyleService;
import com.quickshear.service.OrderService;
import com.quickshear.service.ShopService;
import com.quickshear.service.sms.MessageService;
import com.quickshear.service.sms.StorageService;
import com.shear.front.vo.OrderVo;
import com.shear.front.vo.TenpayPayInfoVo;
import com.shear.front.vo.TenpayPayVo;

@Controller
@RequestMapping("/shear")
public class OrderController extends AbstractController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

    @Autowired
    private ShopService shopService;
    @Autowired
    private HairstyleService hairstyleService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private AccessTokenUtil accessTokenUtil;
    @Autowired
    private WechatTemplateMsgSender wms;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private StorageService storage;
    @Autowired
    private WechatManagerNew manager;
    @Autowired
    private WechatManager wechatManager;
    // 缓存多少条内容
    LRUCache cache = new LRUCache(300);

    @RequestMapping("/order/prepay")
    public String detail(Model model, @ModelAttribute OrderVo vo, HttpSession session,HttpServletRequest request) {
	String openid = storage.get(request, "openid");
	orderVoDecode(vo);
	Shop shop = null;
	Customer user = null;
	try {
	    shop = shopService.findbyid(Long.valueOf(vo.getShopId()));
	    CustomerQuery uq = new CustomerQuery();
	    uq.setWechatOpenId(openid);
	    List<Customer>  userList = customerService.selectByParam(uq);
	    if (userList.size() > 0) {
		user = userList.get(0);
		if (user != null) {
		    vo.setCustomerId(user.getId());
		}
	    }

	} catch (NumberFormatException e) {
	    e.printStackTrace();
	} catch (Exception e) {
	    e.printStackTrace();
	}
	String time = vo.getAppointmentDay() + " " + vo.getAppointmentTime();
	try {

	    OrderQuery q = new OrderQuery();
	    q.setShopId(vo.getShopId());
	    q.setOrderStatus(1);
	    q.setAppointmentTime(DateUtil.parse(time + ":00", DateUtil.ALL));
	    List<Order> list = orderService.selectByParam(q);
	    model.addAttribute("count", list.size());
	} catch (Exception e) {
	    e.printStackTrace();
	}
	model.addAttribute("user", user);
	model.addAttribute("order", vo);
	model.addAttribute("shop", shop);
	return "order/prepay";
    }

    @RequestMapping(value = "/order/sms", method = RequestMethod.POST)
    @ResponseBody
    public String sendSms(String phone) {

	String code = cache.get(phone);
	if (StringUtils.isBlank(code)) {
	    String c = null;
	    try {
		c = messageService.sendRandomCode(phone);
	    } catch (ClientException e) {
		e.printStackTrace();
		LOGGER.error("sms" + e);
	    }
	    cache.set(phone, c);
	    return c;
	} else {
	    cache.remove(phone);
	    return code;
	}
    }

    @RequestMapping(value = "/order/sms/validate", method = RequestMethod.POST)
    @ResponseBody
    public Boolean validate(String phone, String code) {
	String result = (String) cache.get(phone);
	if (code.equals(result)) {
	    cache.remove(phone);
	    return true;
	} else {
	    return false;
	}

    }

    @RequestMapping(value = "/order/pay", method = RequestMethod.POST)
    @ResponseBody
    public TenpayPayVo prepay(@ModelAttribute OrderVo vo, Model model, HttpServletRequest request,
	    HttpServletResponse response) {
	String openid = (String) model.asMap().get("openid");
	Long customerId = null;
	Customer cus = null;
	try {
	    cus = customerService.findbyOpenId(openid);
	} catch (Exception e1) {
	    e1.printStackTrace();
	}
	if (cus == null) {
	    try {
		Customer user = new Customer();
		user.setcTime(new Date());
		user.setmTime(user.getcTime());
		user.setPhoneNumber(vo.getCustomerNumber());
		user.setWechatOpenId(openid);
		int m = customerService.save(user);
		customerId = user.getId();
		LOGGER.info("用户保存结果：" + m);
	    } catch (NumberFormatException e) {
		e.printStackTrace();
	    } catch (Exception e) {
		e.printStackTrace();
	    }
	}else {
	    customerId = cus.getId(); 
	}
	Order order = new Order();
	BeanCopier cp = BeanCopier.create(OrderVo.class, Order.class, false);
	cp.copy(vo, order, null);
	String time = vo.getAppointmentDay() + " " + vo.getAppointmentTime();
	order.setAppointmentTime(DateUtil.parse(time + ":00", DateUtil.ALL));
	order.setmTime(order.getcTime());
	order.setOrderStatus(0);
	order.setCustomerId(customerId);
	if(StringUtils.isBlank(vo.getCustomerNumber()))
	    order.setCustomerNumber(cus.getPhoneNumber());
	try {
	    if (order.getOrderId() == null) {
		order.setOrderId(getOrderId());
		order.setcTime(new Date());
		int r = orderService.save(order);
		LOGGER.info("订单保存结果：" + r);
	    } else {
		int m = orderService.update(order);
		LOGGER.info("订单更新结果：" + m);
	    }

	} catch (Exception e) {
	    e.printStackTrace();
	}
	TenpayPayVo payVo = null;
	try {
	    payVo = generateOrderInfoOfTenpay(order, openid, request, response);
	    LOGGER.info("generateOrderInfoOfTenpay结果：" + payVo);
	} catch (Exception e) {
	    e.printStackTrace();
	}
	return payVo;
    }

    /**
     * 微信支付回调接口
     * 
     * @param request
     * @return
     * @throws IOException
     */
    @RequestMapping(value = "/order/notify", method = RequestMethod.POST,produces = "application/xml")
    @ResponseBody
    public String tenpay(HttpServletRequest request, HttpServletResponse response) throws IOException {

	ResponseHandler resHandler = new ResponseHandler(request, response);
	resHandler.setKey(TenpayConfig.partner_key);
	InputStream is = request.getInputStream();
	BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
	String buffer = null;
	StringBuffer sb = new StringBuffer();
	while ((buffer = br.readLine()) != null) {
	    sb.append(buffer);
	}
	String notifyMessage = sb.toString();
	LOGGER.info("支付|notifyMessage=" + notifyMessage);
	resHandler.doParse(notifyMessage);

	Map<String, String> resultMap = new TreeMap<String, String>();

	if (resHandler.isTenpaySign()) {
	    // 商户订单号
	    String out_trade_no = resHandler.getParameter("out_trade_no");
	    // 财付通订单号
	    String transaction_id = resHandler.getParameter("transaction_id");

	    String return_code = resHandler.getParameter("return_code");

	    // 判断签名及结果
	    if ("SUCCESS".equals(return_code)) {

		Long orderId = Long.valueOf(out_trade_no);
		Order order = null;
		try {
		    order = orderService.findbyid(orderId);
		    if (order != null && order.getOrderStatus().equals(0)) {
			order.setOrderStatus(1);
			order.setCancelReason(transaction_id);
			order.setPayType(1);
			order.setServiceCode(getRandomCode(4));
			int r = orderService.update(order);
			LOGGER.info("微信支付回调修改订单状态:" + order.getOrderId() + " result:" + r);
			WechatTemplateOrderStatusMsg msg = new WechatTemplateOrderStatusMsg();
			// 发送微信模板消息
			msg.setFirstValue("预约成功");
			msg.setKeyword1Value(DateUtil.format(order.getAppointmentTime(), DateUtil.ALL));
			msg.setKeyword2Value(order.getServiceCode());
			msg.setKeyword3Value(order.getOrderId() + "");
			msg.setKeyword4Value(order.getTotalPrice() + "元");
			msg.setRemarkValue("点击查看详情");
			msg.setUrl("http://m.qiansishun.com/shear/order/list");
			msg.setTemplate_id("C96smq2eb2iHCoxeaLBy_3EOMiTy1Pg5zLm0P3kIkbY");
			Customer cus = customerService.findbyid(order.getCustomerId());
			if (cus != null) {
			    msg.setTouser(cus.getWechatOpenId());
			    wms.sendTemplateMsg(msg);
			}
		    } else {
			LOGGER.info("微信支付回调修改订单状态不正确:" + order.getOrderId());
		    }

		} catch (Exception e) {
		    e.printStackTrace();
		    LOGGER.error("error",e);
		}
		
		 resultMap.put("return_code", "<![CDATA[SUCCESS]]>");
		 resultMap.put("return_msg", "<![CDATA[OK]]>");
		 return XMLUtil.map2Xml(resultMap);

	    } else {
		LOGGER.error(String.format("微信支付异步回调|即时到账支付失败，订单号：%s,交易号：%s", out_trade_no, transaction_id));
		resultMap.put("return_code", "FAIL");
		resultMap.put("return_msg", "回调失败,return_code=" + return_code);
		return XMLUtil.map2Xml(resultMap);
	    }
	   
	} else {
	    LOGGER.error("微信支付异步回调|通知签名验证失败:" + resHandler.getParameter("return_msg"));
	    resultMap.put("return_code", "FAIL");
	    resultMap.put("return_msg", "签名验证失败");
	    return XMLUtil.map2Xml(resultMap);
	}
    }

    @RequestMapping("/order/list")
    public String list(Model model,Integer status,String code,String state,HttpServletResponse response) {
	String openid = null;
	if (StringUtils.isNotBlank(code)) {
	    openid = manager.getWechatOpenIdByPageAccess(code);
	    storage.set("openid",openid, response);
	}else {
	    openid=(String) model.asMap().get("openid");
	}
	 Long customerId = null;
	 List<Order> orderList = null;
	 List<OrderVo> orderVoList = null;
	 if(StringUtils.isNotBlank(openid)){
	    try {
		Customer cus = customerService.findbyOpenId(openid);
		LOGGER.info("cus:"+cus);
		if (cus != null) {
		    customerId = cus.getId();
		    OrderQuery query = new OrderQuery();
			query.setCustomerId(customerId);
			if(status == null){
			    //待服务
			    status = 1;
			    List<Integer> statusList = new ArrayList<Integer>();
			    statusList.add(1);
			    statusList.add(50);
			    statusList.add(100);
			    query.setOrderStatusList(statusList);
			}
			
			try {
			    orderList = orderService.selectByParam(query);
			    orderVoList = new ArrayList<OrderVo>();
			    for(Order order:orderList){
				OrderVo vo = new OrderVo();
				BeanCopier cp = BeanCopier.create(Order.class, OrderVo.class, false);
				cp.copy(order,vo,null);
				Shop shop = shopService.findbyid(Long.valueOf(order.getShopId()));
				if(shop !=null){
				    vo.setShopName(shop.getName());
				    vo.setShopAddress(shop.getAddress());
				}
				vo.setAppointmentTime(DateUtil.format(order.getAppointmentTime(), DateUtil.ALL));
				orderVoList.add(vo);
			    }
			} catch (Exception e) {
			    e.printStackTrace();
			    LOGGER.error("error"+e);
			}

		}
	    } catch (Exception e) {
		e.printStackTrace();
		 LOGGER.error("error"+e);
	    }
		
	 }
	 LOGGER.info("orderVoList:"+orderVoList);
	
	model.addAttribute("orderList", orderVoList);
	
	// 通过jsapi拿到经纬度
	String jsapi = wechatManager.getJsapiTicket();
	String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
	String nonceStr = "Wm3WZY" + timestamp;
	// 当前网页的URL，不包含#及其后面部分
	String url = "http://m.qiansishun.com/shear/order/list";
	String sign = wechatManager.getSign(timestamp, nonceStr, url);
	model.addAttribute("jsapi", jsapi);
	model.addAttribute("timestamp", timestamp);
	model.addAttribute("sign", sign);
	model.addAttribute("nonceStr", nonceStr);
	return "order/list";
    }

    /**
     * 生成tenpay的订单支付信息
     * 
     * @param orderVo
     * @param request
     * @param response
     * @return
     */
    private TenpayPayVo generateOrderInfoOfTenpay(Order order, String openid, HttpServletRequest request,
	    HttpServletResponse response) {

	String outTradeNo = order.getOrderId() + "";
	// 获取提交的商品名称
	String sName = "";
	if(order.getShopId()>0){
	    Shop shop = null;
	    try {
		shop = shopService.findbyid(order.getShopId());
	    } catch (NumberFormatException e) {
		e.printStackTrace();
	    } catch (Exception e) {
		e.printStackTrace();
	    }
	    sName = shop.getName();
	}
	
	String productName = StringUtils.abbreviate("仟丝顺+"+sName, 128);
	// String product_name = request.getParameter("product_name");
	TenpayPayVo tenpayPayVo = new TenpayPayVo();
	RequestHandler reqHandler = new RequestHandler(request, response);
	reqHandler.setKey(TenpayConfig.partner_key);
//	reqHandler.setGateUrl(WechatConstat.prepay_url);
	// 获取token值
	String token = accessTokenUtil.getAccessToken();
	if (StringUtils.trimToNull(token) != null) {
	    // 生成预支付单,【统一支付接口】参数
	    // 地址： 地址： https://api.mch.weixin.qq.com/pay/unifiedorder
	    SortedMap<String, String> prePayParams = new TreeMap<String, String>();

	    prePayParams.put("appid", TenpayConfig.app_id);
	    prePayParams.put("mch_id", TenpayConfig.mch_id);
	    prePayParams.put("nonce_str", Sha1Util.getNonceStr());
	    prePayParams.put("body", productName); // 商品描述
	    prePayParams.put("out_trade_no", outTradeNo); // 商家订单号
	    prePayParams.put("total_fee", order.getActualPrice().multiply(new BigDecimal(100)).setScale(0)+""); // 商品金额,以分为单位
	    prePayParams.put("spbill_create_ip", request.getRemoteAddr()); // 订单生成的机器IP，指用户浏览器端IP
	    prePayParams.put("notify_url", TenpayConfig.notify_url); // 接收微信通知的URL
	    prePayParams.put("trade_type", "JSAPI");

	    prePayParams.put("openid", openid);

	    String sign = reqHandler.createSign(prePayParams);
	    prePayParams.put("sign", sign);
	    LOGGER.info("debug:  prePayParams: "+prePayParams);
	    // 获取prepayId
	    String prepayid = reqHandler.sendPrepay(prePayParams);

	    if (StringUtils.isNotBlank(prepayid)) {
		// 签名参数列表
		SortedMap<String, String> payParams = new TreeMap<String, String>();
		payParams.put("appId", TenpayConfig.app_id);
		payParams.put("timeStamp", Sha1Util.getTimeStamp());
		payParams.put("nonceStr", Sha1Util.getNonceStr());
		payParams.put("package", "prepay_id=" + prepayid);
		payParams.put("signType", "MD5");
		String paySign = reqHandler.createSign(payParams);
		payParams.put("paySign", paySign);

		// 输出参数
		tenpayPayVo.setRetCode("0");
		tenpayPayVo.setRetMsg("OK");
		TenpayPayInfoVo payInfoVo = new TenpayPayInfoVo();
		payInfoVo.setAppId(payParams.get("appId"));
		payInfoVo.setTimeStamp(payParams.get("timeStamp"));
		payInfoVo.setNonceStr(payParams.get("nonceStr"));
		payInfoVo.setPackageValue(payParams.get("package"));
		payInfoVo.setSign(payParams.get("paySign"));
		payInfoVo.setSignType(payParams.get("signType"));
		String userAgent = request.getHeader("user-agent");
		char agent = userAgent.charAt(userAgent.indexOf("MicroMessenger") + 15);
		payInfoVo.setAgent(new String(new char[] { agent }));// 微信版本号，用于前面提到的判断用户手机微信的版本是否是5.0以上版本。
		tenpayPayVo.setPayInfo(payInfoVo);

	    } else {
		LOGGER.error(String.format("get prepayid err ,info = %s", prepayid));
		tenpayPayVo.setRetCode("-2");
		tenpayPayVo.setRetMsg("错误：获取prepayId失败");
	    }

	} else {
	    tenpayPayVo.setRetCode("-1");
	    tenpayPayVo.setRetMsg("错误：获取不到Token");
	}
	tenpayPayVo.setOrderId(outTradeNo);
	return tenpayPayVo;
    }

    /**
     * 生成订单号
     * 
     * @return orderId
     */
    private Long getOrderId() {
	// 日期开头
	String ordKey = DateUtil.format(new Date(), "yyMMdd");
	// 随机八位尾数
	Random random = new Random();
	int ordValue = random.nextInt(99999999);
	// 组成订单号
	String orderId = ordKey + String.format("%08d", ordValue);// 尾数不足八位补齐
	return Long.valueOf(orderId);
    }
}
