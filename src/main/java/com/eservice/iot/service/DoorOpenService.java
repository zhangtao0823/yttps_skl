package com.eservice.iot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.eservice.iot.model.ResponseModel;
import com.eservice.iot.model.VisitRecord;
import com.eservice.iot.service.wiegand.WgUdpCommShort;
import com.eservice.iot.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

/**
 * @author HT
 */
@Component
public class DoorOpenService {

    private final static Logger logger = LoggerFactory.getLogger(DoorOpenService.class);

    @Value("${park_base_url}")
    private String PARK_BASE_URL;

    @Value("${door_open}")
    private boolean DOOR_OPEN;

    @Value("${controller_sn}")
    private String CONTROLLER_SN;

    @Autowired
    private RestTemplate restTemplate;
    private SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Token
     */
    private String token;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TagService tagService;

    @Autowired
    private Executor myExecutePool;
    /**
     * 添加允许的标签tag name
     */
    private ArrayList<String> allowedTagNameList = new ArrayList<>();
    /**
     * 添加允许的标签tag id
     */
    private ArrayList<String> allowTagIDList = new ArrayList<>();
    /**
     * 允许的设备id以及对应控制器的IP
     */
    private HashMap<String, String> allowDeviceDoorControlMap = new HashMap<>();

    /**
     * 最近一次的开门时间，1秒内同一扇门不重复开门
     */
    private HashMap<String, Integer> mRecentOpenTimeMap = new HashMap<>();

    private static final int DOOR_OPEN_MIN_TIME = 1;
    /**
     * 门禁控制器IP以及名称
     */
    private HashMap<String, String> deviceDoorNameMap = new HashMap<>();

    /**
     * 查询开始时间,单位为秒
     */
    private Long queryStartTime = 0L;

    public DoorOpenService() {
        //准备初始数据，此时获取到访客列表后不去通知，初始化开始查询时间
        queryStartTime = Util.getDateStartTime().getTime() / 1000;

        allowedTagNameList.add("无感");
        allowedTagNameList.add("test");

        allowDeviceDoorControlMap.put("10.250.62.201", "10.250.62.206");
        allowDeviceDoorControlMap.put("10.250.62.202", "10.250.62.206");

        deviceDoorNameMap.put("10.250.62.201", "1");
        deviceDoorNameMap.put("10.250.62.202", "1");

        for (String deviceId : allowDeviceDoorControlMap.keySet()) {
            mRecentOpenTimeMap.put(deviceId, Integer.valueOf(String.valueOf(System.currentTimeMillis() / 1000)));
        }
    }

    /**
     * 每秒查询一次过人记录
     */
    @Scheduled(fixedRate = 200)
    public void fetchVisitorRecordScheduled() {

        if (DOOR_OPEN && tagService != null && tagService.getmAllTagList().size() > 0) {
            if (token == null && tokenService != null) {
                token = tokenService.getToken();
            }
            if (token != null) {
                HashMap<String, Object> postParameters = new HashMap<>();
                ///考勤记录查询开始时间
                postParameters.put("start_timestamp", queryStartTime);
                ///考勤记录查询结束时间
                Long queryEndTime = System.currentTimeMillis() / 1000;
                //这里加上1秒是为了NTP不准确，设备时间快于服务器时间
                postParameters.put("end_timestamp", queryEndTime + 1);
                HttpHeaders headers = new HttpHeaders();
                headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
                headers.add(HttpHeaders.AUTHORIZATION, token);
                //获取访客和员工的过人记录
                ArrayList<String> identity = new ArrayList<>();
                identity.add("VISITOR");
                identity.add("STAFF");
                postParameters.put("identity_list", identity);
                //添加allowed的标签ID
                if (allowTagIDList.size() == 0) {
                    for (String tagName : allowedTagNameList) {
                        boolean matched = false;
                        for (int i = 0; i < tagService.getmAllTagList().size() && !matched; i++) {
                            if (tagService.getmAllTagList().get(i).getTag_name().equals(tagName)) {
                                allowTagIDList.add(tagService.getmAllTagList().get(i).getTag_id());
                                matched = true;
                            }
                        }
                        if (!matched) {
                            logger.error("Allowed tag name is not found ==> {}", tagName);
                        }
                    }
                }
                postParameters.put("tag_id_list", allowTagIDList);

                HttpEntity httpEntity = new HttpEntity<>(JSON.toJSONString(postParameters), headers);
                try {
                    ResponseEntity<String> responseEntity = restTemplate.postForEntity(PARK_BASE_URL + "/visit_record/query", httpEntity, String.class);
                    if (responseEntity.getStatusCodeValue() == ResponseCode.OK) {
                        String body = responseEntity.getBody();
                        if (body != null) {
                            ResponseModel responseModel = JSONObject.parseObject(body, ResponseModel.class);
                            if (responseModel != null && responseModel.getResult() != null) {
                                ArrayList<VisitRecord> tempList = (ArrayList<VisitRecord>) JSONArray.parseArray(responseModel.getResult(), VisitRecord.class);
                                if (tempList != null && tempList.size() > 0) {
                                    for (VisitRecord record : tempList) {
                                        boolean toOpen = allowDeviceDoorControlMap.get(record.getDevice_id()) != null
                                                && (record.getTimestamp() - mRecentOpenTimeMap.get(record.getDevice_id())) > DOOR_OPEN_MIN_TIME;
                                        if (toOpen) {
                                            mRecentOpenTimeMap.put(record.getDevice_id(), record.getTimestamp());
                                            logger.info("Open the door ==> {} by [{}]", record.getDevice_id(), record.getPerson().getPerson_information().getName());
                                            openDoor(allowDeviceDoorControlMap.get(record.getDevice_id()), deviceDoorNameMap.get(record.getDevice_id()));
                                        }
                                    }
                                    //以上一次抓拍到的时间作为下一次查询的开始时间
                                    //queryStartTime = (long) tempList.get(0).getTimestamp()+1;
                                    ///此处不减1是因为本身无感开门不需要很精确
                                    queryStartTime = queryEndTime;
                                }
                            }
                        }
                    }
                } catch (HttpClientErrorException exception) {
                    if (exception.getStatusCode().value() == ResponseCode.TOKEN_INVALID) {
                        //token失效,重新获取token后再进行数据请求
                        token = tokenService.getToken();
                    }
                }
            } else {
                logger.error("Token is null, fetch visitor record error!");
            }
        }
        //openDoor("192.168.0.111", "m001-1号");
    }

    private void openDoor(String doorControllerIP, String doorName) {
        WgUdpCommShort pkt = new WgUdpCommShort();
        pkt.CommOpen(doorControllerIP);
        pkt.functionID = (byte) 0x40;
        pkt.iDevSn = Long.valueOf(CONTROLLER_SN);
        pkt.data[0] =(byte) (Integer.valueOf(doorName) & 0xff);
        byte[] recvBuff = pkt.run();
        if (recvBuff != null) {
            if (WgUdpCommShort.getIntByByte(recvBuff[8]) == 1)
            {
                //有效开门.....
                logger.info("开门成功!");
            } else {
                logger.warn("开门失败!");
            }
        } else {
            logger.warn("开门失败！");
        }
        pkt.CommClose();
        pkt = null;

    }
}
