package com.eservice.iot.model;

import java.lang.reflect.Array;
import java.util.ArrayList;

/**
 * Class Description: 部门签到情况
 *
 * @author Wilson Hu
 * @date 2018/11/13
 */
public class DepartmentSignData {
    /**
     * 部门标签的ID
     */
    private String tagId;
    /**
     * 部门签到总数
     */
    private ArrayList<Staff> totalStaff = new ArrayList<>();
    /**
     * 部门目前签到总数
     */
    private ArrayList<VisitRecord> currentRecordList;

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public ArrayList<Staff> getTotalStaff() {
        return totalStaff;
    }

    public void setTotalStaff(ArrayList<Staff> totalStaff) {
        this.totalStaff = totalStaff;
    }

    public ArrayList<VisitRecord> getCurrentRecordList() {
        return currentRecordList;
    }

    public void setCurrentRecordList(ArrayList<VisitRecord> currentRecordList) {
        this.currentRecordList = currentRecordList;
    }
}

