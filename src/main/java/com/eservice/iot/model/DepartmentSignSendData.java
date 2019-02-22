package com.eservice.iot.model;

/**
 * Class Description:
 *
 * @author Wilson Hu
 * @date 2018/11/13
 */
public class DepartmentSignSendData {
    private String tagId;
    private int totalNum;
    private int currentNum;

    public String getTagId() {
        return tagId;
    }

    public void setTagId(String tagId) {
        this.tagId = tagId;
    }

    public int getTotalNum() {
        return totalNum;
    }

    public void setTotalNum(int totalNum) {
        this.totalNum = totalNum;
    }

    public int getCurrentNum() {
        return currentNum;
    }

    public void setCurrentNum(int currentNum) {
        this.currentNum = currentNum;
    }
}
