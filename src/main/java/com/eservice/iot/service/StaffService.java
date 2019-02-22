package com.eservice.iot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.eservice.iot.model.*;
import com.eservice.iot.util.Util;
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

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;


/**
 * @author HT
 */
@Component
public class StaffService {

    private final static Logger logger = LoggerFactory.getLogger(StaffService.class);

    @Value("${park_base_url}")
    private String PARK_BASE_URL;

    @Value("${attendance_begin_time}")
    private int ATTENDANCE_BEGIN_TIME;

    @Value("${attendance_end_time}")
    private int ATTENDANCE_END_TIME;

    @Autowired
    private RestTemplate restTemplate;
    private SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Token
     */
    private String token;
    /**
     * 员工列表
     */
    private ArrayList<Staff> staffList = new ArrayList<>();

    /**
     * 当天已签到员工列表：早上增加，下班后逐渐减少
     */
    private ArrayList<VisitRecord> staffSignInList = new ArrayList<>();

    /**
     * 当天下班员工列表
     */
    private ArrayList<VisitRecord> staffSignOutList = new ArrayList<>();

    /**
     * 当天已签到VIP员工列表
     */
    private ArrayList<VisitRecord> vipSignInList = new ArrayList<>();

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TagService tagService;

    @Resource
    private SurveillancePolicyService surveillancePolicyService;

    private ThreadPoolTaskExecutor mExecutor;

    /**
     * 查询开始时间,单位为秒
     */
    private Long queryStartTime = 0L;

    @Autowired
    private MqttMessageHelper mqttMessageHelper;

    private HashMap<String, DepartmentSignData> mDepartmentSignData = new HashMap<>();

    /**
     * VIP员工在大屏再次出现的最小时间：20分钟
     */
    public static final int VIP_SHOW_TIME = 20 * 60;
    /**
     * 需要考勤的设备ID列表
     */
    private static ArrayList<String> YINGBIN_DEVICE_LIST = new ArrayList<>();
    private static ArrayList<String> mMovingList = new ArrayList<>();
    private static ArrayList<VisitRecord> mSendVipList = new ArrayList<>();
    private static ArrayList<String> CURRENT_ATTENDANCE = new ArrayList<>();


    public StaffService() {
        //准备初始数据，此时获取到考勤列表后不去通知钉钉，初始化开始查询时间
        queryStartTime = Util.getDateStartTime().getTime() / 1000;

        YINGBIN_DEVICE_LIST.add("192.169.200.65");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:20");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:28");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:33");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:39");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:40");
        YINGBIN_DEVICE_LIST.add("f0:41:c8:cf:00:51");
    }

    /**
     * 每秒查询一次考勤信息
     */
    @Scheduled(initialDelay = 5000, fixedRate = 500)
    public void fetchSignInScheduled() {
        ///当员工列表数为0，或者已全部签核完成,以及当前处于程序初始化状态情况下，可以跳过不再去获取考勤数据
        boolean skip = staffList.size() <= 0 || tagService == null || !tagService.isTagInitialFinished();
        if (skip) {
            return;
        }
        if (token == null && tokenService != null) {
            token = tokenService.getToken();
        }
        if (token != null) {
            querySignInStaff(queryStartTime);
        }
    }

    /**
     * 每分钟获取一次需要签到的员工信息
     */
    @Scheduled(initialDelay = 3000, fixedRate = 1000)
    public void fetchStaffScheduled() {
        if (token == null && tokenService != null) {
            token = tokenService.getToken();
        }
        if (token != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.ACCEPT, "application/json");
            headers.add("Authorization", token);
            HttpEntity entity = new HttpEntity(headers);
            try {
                String url = PARK_BASE_URL + "/staffs?";
                for (String tagId : surveillancePolicyService.getmAttendTagIdList()) {

                    url += "tag_id_list=" + tagId + "&";
                }
                if (surveillancePolicyService.getmAttendTagIdList().size() > 0) {
                    url += "&page=0&size=0";
                } else {
                    url += "page=0&size=0";
                }
                ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (responseEntity.getStatusCodeValue() == ResponseCode.OK) {
                    String body = responseEntity.getBody();
                    if (body != null) {
                        processStaffResponse(body);
                    } else {
                        fetchStaffScheduled();
                    }
                }
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() == ResponseCode.TOKEN_INVALID) {
                    token = tokenService.getToken();
                    if (token != null) {
                        fetchStaffScheduled();
                    }
                }
            }
        }
    }

    /**
     * 凌晨1点清除签到记录
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void resetStaffDataScheduled() {
        logger.info("每天凌晨一点清除前一天签到记录：{}", formatter.format(new Date()));

        logger.info("================ 下班未签出记录 ===============");
        if (staffSignInList != null && staffSignInList.size() > 0) {
            for (VisitRecord item : staffSignInList) {
                logger.info("姓名：{}， 时间：{}", item.getPerson().getPerson_information().getName(), formatter.format(new Date((long) item.getTimestamp() * 1000)));
            }
            staffSignInList.clear();
        }

        logger.info("================ 下班记录 ===============");
        if (staffSignOutList != null && staffSignOutList.size() > 0) {
            for (VisitRecord item : staffSignOutList) {
                logger.info("姓名：{}， 下班时间：{}", item.getPerson().getPerson_information().getName(), formatter.format(new Date((long) item.getTimestamp() * 1000)));
            }
            staffSignOutList.clear();
        }

        if (vipSignInList != null && vipSignInList.size() > 0) {
            vipSignInList.clear();
        }

        logger.info("================ 人员信息初始化 ===============");
        if (mDepartmentSignData != null && mDepartmentSignData.size() > 0) {
            mDepartmentSignData.clear();
        }
        //通过MQTT将员工签到信息发送至web端
        logger.warn("Send message to client to clear sign in data!");
        //mqttMessageHelper.sendToClient("staff/sign_in/reset", "{}");
    }

    private void processStaffResponse(String body) {
        ResponseModel responseModel = JSONObject.parseObject(body, ResponseModel.class);
        if (responseModel != null && responseModel.getResult() != null) {
            ArrayList<Staff> tmpList = (ArrayList<Staff>) JSONArray.parseArray(responseModel.getResult(), Staff.class);
            if (tmpList != null && tmpList.size() != 0) {
                tmpList = filterAttendanceStaff(tmpList);
                for (int i = 0; i < tmpList.size(); i++) {
                    List<String> tagIds = tmpList.get(i).getTag_id_list();
                    for (String id : tagIds) {
                        //员工的标签可能存在多个，取一个考勤标签
                        if (surveillancePolicyService.getmAttendTagIdList().contains(id)) {
                            if (mDepartmentSignData.get(id) == null) {
                                DepartmentSignData signData = new DepartmentSignData();
                                signData.setTagId(id);
                                signData.getTotalStaff().add(tmpList.get(i));
                                signData.setCurrentRecordList(new ArrayList<>());
                                mDepartmentSignData.put(id, signData);
                            } else {
                                DepartmentSignData data = mDepartmentSignData.get(id);
                                ArrayList<Staff> staffList = data.getTotalStaff();
                                boolean exist = false;
                                for (int j = 0; j < staffList.size() && !exist; j++) {
                                    if (staffList.get(j).getStaffId().equals(tmpList.get(i).getStaffId())) {
                                        exist = true;
                                    }
                                }
                                if (!exist) {
                                    staffList.add(tmpList.get(i));
                                }
                            }
                            //找到考勤标签就结束，多个考勤标签只考虑第一个
                            break;
                        }
                    }
                }
                if (!staffList.equals(tmpList)) {
                    logger.info("Sign in department number: {}", mDepartmentSignData.size());
                    logger.info("The number of staff：{} ==> {}", staffList.size(), tmpList.size());
                    staffList = tmpList;
                }
            }
        }
    }

    private void processStaffSignInResponse(ArrayList<VisitRecord> records, boolean initial) {
        Collections.reverse(records);
        for (VisitRecord visitRecord : records) {
            ArrayList<VisitRecord> mSendList = new ArrayList<>();

            boolean dataChanged = false;
            List<String> tagList = visitRecord.getPerson().getTag_id_list();
            ///签到相关
            boolean needSignIn = true;
            if (tagList != null) {
                for (int i = 0; i < tagList.size() && needSignIn; i++) {
                    ///在不在考勤标签列表中
                    if (tagService.getNotSignInTagIdList().contains(tagList.get(i))) {
                        needSignIn = false;
                    }
                }
            }
            if (needSignIn && getAttencanceId(visitRecord) != null) {
                //dataChanged = true;
                if (mMovingList.size() < 3 && !initial) {
                    if (!mMovingList.contains(getAttencanceId(visitRecord))) {
                        mMovingList.add(getAttencanceId(visitRecord));
                    }
                }
                if (((long) visitRecord.getTimestamp() * 1000) >= Util.formatAttendanceTime(ATTENDANCE_BEGIN_TIME).getTime()
                        && ((long) visitRecord.getTimestamp() * 1000) < Util.formatAttendanceTime(ATTENDANCE_END_TIME).getTime()) {
                    boolean exit = false;
                    for (int i = 0; i < staffSignInList.size() && !exit; i++) {
                        if (staffSignInList.get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                            exit = true;
                        }
                    }
                    if (!exit) {
                        DepartmentSignData data = mDepartmentSignData.get(getAttencanceId(visitRecord));
                        data.getCurrentRecordList().add(visitRecord);
                        staffSignInList.add(visitRecord);
                        if (!initial) {
                            //dataChangedSet.add(getAttencanceId(visitRecord));
                            dataChanged = true;
                            //TODO:发送考勤签到消息

                        }
                    }
                } else if (((long) visitRecord.getTimestamp() * 1000) >= Util.formatAttendanceTime(ATTENDANCE_END_TIME).getTime()) {
                    int removeIndex = -1;
                    for (int i = 0; i < staffSignInList.size(); i++) {
                        if (staffSignInList.get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                            removeIndex = i;
                            break;
                        }
                    }
                    if (removeIndex != -1) {
                        staffSignInList.remove(removeIndex);
                        DepartmentSignData data = mDepartmentSignData.get(getAttencanceId(visitRecord));
                        int index = -1;
                        for (int i = 0; i < data.getCurrentRecordList().size(); i++) {
                            if (data.getCurrentRecordList().get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                                index = i;
                                break;
                            }
                        }
                        if (index != -1) {
                            //早上去签到的签出
                            data.getCurrentRecordList().remove(index);
                            //dataChangedSet.add(getAttencanceId(visitRecord));
                            //dataChanged = true;
                        }
                    }
                    boolean exist = false;
                    int existIndex = -1;
                    for (int i = 0; i < staffSignOutList.size() && !exist; i++) {
                        if (staffSignOutList.get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                            exist = true;
                            existIndex = i;
                        }
                    }
                    if (!exist) {
                        //如果该员工为签出，则进行签出，并进行消息通知
                        staffSignOutList.add(visitRecord);
                        if (!initial) {
                            //虽然早上没签到，但是签出的动作还是显示，部门签出人数不变
                            dataChanged = true;
                            //TODO:不管早上有无签到，都需要发送下班考勤消息

                        }
                    } else {
                        //进行替换的原因是以后该列表可以作为考察员工下班时间（最后被捕获到时间）
                        staffSignOutList.set(existIndex, visitRecord);
                    }
                } else {
                    logger.info("Invalid attendance：{}; record time：{}", visitRecord.getPerson().getPerson_information().getName(), formatter.format(new Date((long) visitRecord.getTimestamp() * 1000)));
                }
                if(!initial) {
                    if (!CURRENT_ATTENDANCE.contains(visitRecord.getPerson().getPerson_id())) {
                        if(CURRENT_ATTENDANCE.size() >= 5) {
                            CURRENT_ATTENDANCE.remove(0);
                        }
                        CURRENT_ATTENDANCE.add(visitRecord.getPerson().getPerson_id());
                        mSendList.add(visitRecord);
                    }
                }
            }

            ///VIP相关
            boolean isVIP = false;
            if (tagList != null) {
                for (int i = 0; i < tagList.size() && !isVIP; i++) {
                    ///在VIP标签列表中
                    if (tagService.getVIPTagIdList().contains(tagList.get(i)) && YINGBIN_DEVICE_LIST.contains(visitRecord.getDevice_id())) {
                        isVIP = true;
                    }
                }
            }
            if (isVIP) {
                int index = -1;
                for (int i = 0; i < vipSignInList.size() && index == -1; i++) {
                    if (vipSignInList.get(i).getPerson().getPerson_id().equals(visitRecord.getPerson().getPerson_id())) {
                        index = i;
                    }
                }
                if (index == -1) {
                    vipSignInList.add(visitRecord);
                    if (!initial) {
                        synchronized (StaffService.class) {
                            mSendVipList.add(visitRecord);
                        }
                    }
                } else {
                    if (visitRecord.getTimestamp() - vipSignInList.get(index).getTimestamp() >= VIP_SHOW_TIME) {
                        vipSignInList.set(index, visitRecord);
                        synchronized (StaffService.class) {
                            mSendVipList.add(visitRecord);
                        }
                    }
                }
            }
            //建立线程池发送钉钉
            if(mSendList.size() > 0) {
                if(mExecutor == null) {
                    initExecutor();
                }
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        //通过MQTT将员工签到信息发送至web端
                        logger.warn("Send sign in staff to web, size ==> {}", mSendList.size() );
//                    String tagId = getAttencanceId(visitRecord);
//                    String tagName = tagService.getTagName(tagId);
//                    if(!"".equals(tagName)) {
//                        ArrayList<VisitRecord> tmp = new ArrayList<>();
//                        tmp.add(visitRecord);
//                        mqttMessageHelper.sendToClient("staff/sign_in", JSON.toJSONString(tmp));
//                    } else {
//                        logger.warn("Can not find tag name by {}", tagId);
//                    }
                        mqttMessageHelper.sendToClient("staff/sign_in", JSON.toJSONString(mSendList));
                    }
                });
            }

            if (dataChanged) {
                DepartmentSignSendData sendData = new DepartmentSignSendData();
                String tagId = getAttencanceId(visitRecord);
                sendData.setTagId(tagId);
                sendData.setCurrentNum(mDepartmentSignData.get(tagId).getCurrentRecordList().size());
                sendData.setTotalNum(mDepartmentSignData.get(tagId).getTotalStaff().size());
//                synchronized (StaffService.class) {
//                    mSendSignInList.add(sendData);
//                }
                logger.info("Record: {}, device : {}", visitRecord.getPerson().getPerson_information().getName(), visitRecord.getDevice_id());
            }

//            if (sendVipList.size() > 1) {
//                for (VisitRecord item: sendVipList) {
//                    mExecutor.execute(new Runnable() {
//                        @Override
//                        public void run() {
//                            //通过MQTT将VIP签到信息发送至web端的VIP页面
//                            logger.warn("Send VIP staff to web, name ==> {}", item.getPerson().getPerson_information().getName());
//                            ArrayList<VisitRecord> list = new ArrayList<>();
//                            list.add(item);
//                            mqttMessageHelper.sendToClient("staff/vip/sign_in", JSON.toJSONString(list));
//                        }
//                    });
//                }
//            } else if(sendVipList.size() == 1) {
//                //通过MQTT将VIP签到信息发送至web端的VIP页面
//                logger.warn("Send VIP staff to web, name ==> {}", sendVipList.get(0).getPerson().getPerson_information().getName());
//                mqttMessageHelper.sendToClient("staff/vip/sign_in", JSON.toJSONString(sendVipList));
//            }
        }
    }

    private void querySignInStaff(Long startTime) {
        if (token == null) {
            token = tokenService.getToken();
        }
        HashMap<String, Object> postParameters = new HashMap<>();
//        ///考勤记录查询开始时间
        postParameters.put("start_timestamp", startTime);
//        ///考勤记录查询结束时间
        Long queryEndTime = System.currentTimeMillis() / 1000;
        //重启状态
        if (startTime == Util.getDateStartTime().getTime() / 1000) {
            if (queryEndTime > Util.formatAttendanceTime(ATTENDANCE_END_TIME).getTime() / 1000) {
                queryEndTime = Util.formatAttendanceTime(ATTENDANCE_END_TIME).getTime() / 1000;
            }
        }
        postParameters.put("end_timestamp", queryEndTime);
        //只获取员工数据
        ArrayList<String> identity = new ArrayList<>();
        identity.add("STAFF");
        postParameters.put("identity_list", identity);
        //只获取指定考勤设备的过人记录
        postParameters.put("device_id_list", surveillancePolicyService.getmAttendDeviceIdList());
        ///只获取指定考勤tag的过人记录
        postParameters.put("tag_id_list", surveillancePolicyService.getmAttendTagIdList());

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
        headers.add(HttpHeaders.AUTHORIZATION, token);
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
                            processStaffSignInResponse(tempList, startTime.equals(Util.getDateStartTime().getTime() / 1000));
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
                querySignInStaff(startTime);
            }
        }
    }

    private ArrayList<Staff> filterAttendanceStaff(List<Staff> list) {
        ArrayList<Staff> resultList = new ArrayList<>();
        int notNeedNum = 0;
        for (Staff item : list) {
            boolean notNeed = false;
            for (String tagId : item.getTag_id_list()) {
                if (tagService.getNotSignInTagIdList().contains(tagId)) {
                    notNeed = true;
                    notNeedNum++;
                }
            }
            if (!notNeed) {
                resultList.add(item);
            }
        }
        return resultList;
    }

    private String getAttencanceId(VisitRecord record) {
        String tagId = null;
        //返回第一个考勤的标签ID
        if (record != null && record.getPerson().getTag_id_list().size() > 0 && tagId == null) {
            for (String id : record.getPerson().getTag_id_list()) {
                if (surveillancePolicyService.getmAttendTagIdList().contains(id)) {
                    tagId = id;
                }
            }
        }
        return tagId;
    }

    public boolean deleteStaff(String id) {
        boolean success = false;
        if (token == null && tokenService != null) {
            token = tokenService.getToken();
        }
        if (token != null) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.ACCEPT, "application/json");
            headers.add("Authorization", token);
            HttpEntity entity = new HttpEntity(headers);
            try {
                ResponseEntity<String> responseEntity = restTemplate.exchange(PARK_BASE_URL + "/staffs/" + id, HttpMethod.DELETE, entity, String.class);
                if (responseEntity.getStatusCodeValue() == ResponseCode.OK) {
                    String body = responseEntity.getBody();
                    if (body != null) {
                        ResponseModel responseModel = JSONObject.parseObject(body, ResponseModel.class);
                        if(responseModel != null && responseModel.getRtn() == 0) {
                            success = true;
                        }
                    }
                }
            } catch (HttpClientErrorException exception) {
                if (exception.getStatusCode().value() == ResponseCode.TOKEN_INVALID) {
                    token = tokenService.getToken();
                    if (token != null) {
                        deleteStaff(id);
                    }
                }
            }
        }
        return success;
    }

    private void initExecutor() {
        mExecutor = new ThreadPoolTaskExecutor();
        mExecutor.setCorePoolSize(10);
        mExecutor.setMaxPoolSize(100);
        mExecutor.setThreadNamePrefix("YTTPS-");
        mExecutor.initialize();
    }

    public ArrayList<Staff> getStaffList() {
        return staffList;
    }

    public ArrayList<VisitRecord> getStaffSignInList() {
        return staffSignInList;
    }

    public ArrayList<VisitRecord> getVipSignInList() {
        return vipSignInList;
    }

    public ArrayList<String> getMovingList() {
        return mMovingList;
    }

    public ArrayList<VisitRecord> getSendingVipList() {
        return mSendVipList;
    }

    public HashMap<String, DepartmentSignData> getmDepartmentSignData() {
        return mDepartmentSignData;
    }

    public void setmDepartmentSignData(HashMap<String, DepartmentSignData> mDepartmentSignData) {
        this.mDepartmentSignData = mDepartmentSignData;
    }

    public ArrayList<String> getYingbinDeviceList() {
        return YINGBIN_DEVICE_LIST;
    }
}
