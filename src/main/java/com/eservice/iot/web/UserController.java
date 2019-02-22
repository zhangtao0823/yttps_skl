package com.eservice.iot.web;
import com.eservice.iot.core.Result;
import com.eservice.iot.core.ResultGenerator;
import com.eservice.iot.model.DepartmentSignData;
import com.eservice.iot.model.DepartmentSignSendData;
import com.eservice.iot.model.VisitRecord;
import com.eservice.iot.service.StaffService;
import com.eservice.iot.service.VisitorService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.util.*;

/**
* Class Description: xxx
* @author Wilson Hu
* @date 2018/08/21.
*/
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private StaffService staffService;
    @Resource
    private VisitorService visitorService;

    /**
     * 该值为default值， Android端传入的参数不能为“0”
     */
    private static String ZERO_STRING = "0";


    @GetMapping("/getStaffNum")
    public Result getStaffNum() {
        return ResultGenerator.genSuccessResult(staffService.getStaffList().size());
    }

    @GetMapping("/getStaffSignInNum")
    public Result getStaffSignInNum() {
        return ResultGenerator.genSuccessResult(staffService.getStaffSignInList().size());
    }

    @GetMapping("/getTotalSignData")
    public Result getTotalSignData() {
        return ResultGenerator.genSuccessResult(staffService.getmDepartmentSignData().values());
    }

    @GetMapping("/getSendingVipList")
    public Result getSendingVipList() {
        ArrayList<VisitRecord> list = new ArrayList<>();
        list.addAll(staffService.getSendingVipList());
        list.addAll(visitorService.getSendingVipList());
        synchronized (StaffService.class) {
            staffService.getSendingVipList().clear();
        }
        synchronized (VisitorService.class) {
            visitorService.getSendingVipList().clear();
        }
        return ResultGenerator.genSuccessResult(list);
    }

    @GetMapping("/getMovingList")
    public Result getMovingList() {
        ArrayList<String> list = new ArrayList<>();
        list.addAll(staffService.getMovingList());
        synchronized (StaffService.class) {
            staffService.getMovingList().clear();
        }
        return ResultGenerator.genSuccessResult(list);
    }
	
    @GetMapping("/getSignInOutList")
    public Result getCurrentSignInList() {
        ArrayList<DepartmentSignSendData> list = new ArrayList<>();
        HashMap<String, DepartmentSignData> departmentSignDataSet = staffService.getmDepartmentSignData();
        Iterator iterator = departmentSignDataSet.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            DepartmentSignData signData = (DepartmentSignData)entry.getValue();
            DepartmentSignSendData sendData = new DepartmentSignSendData();
            sendData.setTagId(signData.getTagId());
            sendData.setTotalNum(signData.getTotalStaff().size());
            sendData.setCurrentNum(signData.getCurrentRecordList().size());
            list.add(sendData);
        }
        //staffService.getSendingSignInList().clear();
        return ResultGenerator.genSuccessResult(list);
    }

	
    @GetMapping("/getInitData")
    public Result getInitData() {
        ArrayList<DepartmentSignSendData> resultList = new ArrayList<>();
        HashMap<String, DepartmentSignData> map = staffService.getmDepartmentSignData();
        Iterator iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            DepartmentSignData signData = (DepartmentSignData)entry.getValue();
            DepartmentSignSendData  data = new DepartmentSignSendData();
            data.setTagId((String) entry.getKey());
            data.setCurrentNum(signData.getCurrentRecordList().size());
            data.setTotalNum(signData.getTotalStaff().size());
            resultList.add(data);
        }
        return ResultGenerator.genSuccessResult(resultList);
    }
}
