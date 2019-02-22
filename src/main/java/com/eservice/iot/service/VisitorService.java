package com.eservice.iot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dingtalk.api.response.OapiUserListResponse;
import com.eservice.iot.model.*;
import com.eservice.iot.util.Util;
import com.taobao.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static com.eservice.iot.service.StaffService.VIP_SHOW_TIME;


/**
 * @author HT
 */
@Component
public class VisitorService {

    private final static Logger logger = LoggerFactory.getLogger(VisitorService.class);

    @Value("${park_base_url}")
    private String PARK_BASE_URL;

    /**
     * 长期有效的访客
     */
    public static final String LONG_TIME_VALID = "0";

    /**
     * 临时访客
     */
    public static final String TMP_VALID = "1";

    @Autowired
    private RestTemplate restTemplate;
    private SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");


    /**
     * Token
     */
    private String token;
    /**
     * 访客列表
     */
    private List<Visitor> visitorList = new ArrayList<>();

    /**
     * 当天访客到访列表
     */
    private ArrayList<VisitRecord> visitorSignInList = new ArrayList<>();

    /**
     * 当天已签到VIP访客列表
     */
    private ArrayList<VisitRecord> vipVisitorList = new ArrayList<>();

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TagService tagService;

    @Autowired
    private StaffService staffService;

    /**
     * 查询开始时间,单位为秒
     */
    private Long queryStartTime = 0L;

    private ThreadPoolTaskExecutor mExecutor;

    @Autowired
    //private MqttMessageHelper mqttMessageHelper;

    private static ArrayList<VisitRecord> mSendingVipList = new ArrayList<>();


    public VisitorService() {
        //准备初始数据，此时获取到访客列表后不去通知，初始化开始查询时间
        queryStartTime = Util.getDateStartTime().getTime() / 1000;
    }

    /**
     * 凌晨1点清除签到记录
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void resetStaffDataScheduled() {
        logger.info("清除VIP访客签到记录：{}", formatter.format(new Date()));

        if (vipVisitorList != null && vipVisitorList.size() > 0) {
            vipVisitorList.clear();
        }
    }

    /**
     * 每秒查询访客记录
     */
    @Scheduled(initialDelay = 5000,fixedRate = 1000)
    public void fetchVisitorRecordScheduled() {
        boolean skip = visitorList.size() <= 0 || visitorList.size() == visitorSignInList.size()
                || tagService == null || !tagService.isTagInitialFinished();
        if (skip) {
            return;
        }

        if (token == null && tokenService != null) {
            token = tokenService.getToken();
        }
        if( token != null) {
            querySignInVisitor(queryStartTime);
        } else {
            logger.error("Token is null, fetch visitor record error!");
        }
    }

    /**
     * 每10秒钟获取一次当天访客信息
     */
    @Scheduled(initialDelay = 5000,fixedRate = 1000 * 10)
    public void fetchVisitorListScheduled() {
        if (token == null) {
            token = tokenService.getToken();
        }
        if(token != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.ACCEPT, "application/json");
            headers.add("Authorization", token);
            HttpEntity entity = new HttpEntity(headers);
            try {
                ResponseEntity<String> responseEntity = restTemplate.exchange(PARK_BASE_URL + "/visitors", HttpMethod.GET, entity, String.class);
                if (responseEntity.getStatusCodeValue() == ResponseCode.OK) {
                    String body = responseEntity.getBody();
                    if (body != null) {
                        processVisitorResponse(body);
                    } else {
                        fetchVisitorListScheduled();
                    }
                }
            } catch (HttpClientErrorException errorException) {
                if (errorException.getStatusCode().value() == ResponseCode.TOKEN_INVALID) {
                    token = tokenService.getToken();
                    fetchVisitorListScheduled();
                }
            }
        }
    }

    private void processVisitorResponse(String body) {
        ResponseModel responseModel = JSONObject.parseObject(body, ResponseModel.class);
        if (responseModel != null && responseModel.getResult() != null) {
            List<Visitor> tmpList = JSONArray.parseArray(responseModel.getResult(), Visitor.class);
            if (tmpList != null && tmpList.size() > 0) {
                boolean changed = false;
                if (tmpList.size() != visitorList.size()) {
                    changed = true;
                } else {
                    if (!tmpList.equals(visitorList)) {
                        changed = true;
                    }
                }
                if (changed) {
                    logger.info("The number of visitor：{} ==> {}", visitorList.size(), tmpList.size());
                }
                visitorList = tmpList;
            }
        }
    }

    private void processVisitorRecordResponse(ArrayList<VisitRecord> tempList, boolean initial) {
        if (tempList != null && tempList.size() > 0) {
            for (VisitRecord visitRecord : tempList) {
                List<String> tagList = visitRecord.getPerson().getTag_id_list();
                boolean isVIP = false;
                if (tagList != null) {
                    for (int i = 0; i < tagList.size() && !isVIP; i++) {
                        ///在VIP标签列表中
                        if (tagService.getVIPTagIdList().contains(tagList.get(i))
                                && staffService.getYingbinDeviceList().contains(visitRecord.getDevice_id())) {
                            isVIP = true;
                        }
                    }
                }
                if (isVIP) {
                    int index = -1;
                    for (int i = 0; i < vipVisitorList.size() && index == -1; i++) {
                        if (vipVisitorList.get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                            index = i;
                        }
                    }
                    if (index == -1) {
                        vipVisitorList.add(visitRecord);
                        if (!initial) {
                            synchronized (VisitorService.class) {
                                mSendingVipList.add(visitRecord);
                            }
                        }
                    } else {
                        if(visitRecord.getTimestamp() - vipVisitorList.get(index).getTimestamp() >= VIP_SHOW_TIME ) {
                            vipVisitorList.set(index,visitRecord);
                            if(!initial) {
                                synchronized (VisitorService.class) {
                                    mSendingVipList.add(visitRecord);
                                }
                            }
                        }
                    }
                }
                if(mExecutor == null) {
                    initExecutor();
                }
//                if (sendVipList.size() > 1) {
//                    for (VisitRecord item: sendVipList) {
//                        mExecutor.execute(new Runnable() {
//                            @Override
//                            public void run() {
//                                //通过MQTT将VIP签到信息发送至web端的VIP页面
//                                ArrayList<VisitRecord> list = new ArrayList<>();
//                                list.add(item);
//                                mqttMessageHelper.sendToClient("visitor/vip/sign_in", JSON.toJSONString(list));
//                                logger.info("Send visitor data to web, name: {}", item.getPerson().getPerson_information().getName());
//                            }
//                        });
//                    }
//                } else if(sendVipList.size() == 1) {
//                    mqttMessageHelper.sendToClient("visitor/vip/sign_in", JSON.toJSONString(sendVipList));
//                    logger.info("Send visitor data to web, name: {}", sendVipList.get(0).getPerson().getPerson_information().getName());
//                }
            }
            //以上一次抓拍到的时间作为下一次查询的开始时间
            //queryStartTime = (long) tempList.get(0).getTimestamp()+1;
        }
    }


    private void querySignInVisitor(Long startTime) {
        HashMap<String, Object> postParameters = new HashMap<>();
        ///考勤记录查询开始时间
        postParameters.put("start_timestamp", startTime);
        ///考勤记录查询结束时间
        Long queryEndTime = System.currentTimeMillis() / 1000;
        postParameters.put("end_timestamp", queryEndTime);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.add(HttpHeaders.AUTHORIZATION, token);
        //只获取访客数据
        ArrayList<String> identity = new ArrayList<>();
        identity.add("VISITOR");
        HttpEntity httpEntity = new HttpEntity<>(JSON.toJSONString(postParameters), headers);
        try {
            ResponseEntity<String> responseEntity = restTemplate.postForEntity(PARK_BASE_URL + "/visitor_visit/record", httpEntity, String.class);
            if (responseEntity.getStatusCodeValue() == ResponseCode.OK) {
                String body = responseEntity.getBody();
                if (body != null) {
                    ResponseModel responseModel = JSONObject.parseObject(body, ResponseModel.class);
                    if (responseModel != null && responseModel.getResult() != null) {
                        ArrayList<VisitRecord> tempList = (ArrayList<VisitRecord>) JSONArray.parseArray(responseModel.getResult(), VisitRecord.class);
                        if (tempList != null && tempList.size() > 0) {
                            processVisitorRecordResponse(tempList, startTime.equals(Util.getDateStartTime().getTime() / 1000));
                            //query成功后用上一次查询的结束时间作为下一次开始时间，减去1秒形成闭区间，
                            // 这里的时间是服务器时间，所以跟门禁或者抓拍机不一定是一个时间，容易遗漏
                            queryStartTime = queryEndTime - 1;
                        }
                    }
                }
            }
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode().value() == ResponseCode.TOKEN_INVALID) {
                //token失效,重新获取token后再进行数据请求
                token = tokenService.getToken();
                querySignInVisitor(startTime);
            }
        }
    }

    private void initExecutor() {
        mExecutor = new ThreadPoolTaskExecutor();
        mExecutor.setCorePoolSize(10);
        mExecutor.setMaxPoolSize(100);
        mExecutor.setThreadNamePrefix("YTTPS-");
        mExecutor.initialize();
    }

    public List<Visitor> getVisitorList() {
        return visitorList;
    }

    public List<VisitRecord> getSendingVipList() {
        return mSendingVipList;
    }

}
