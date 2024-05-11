package com.vone.vmq;


/**
 * 将需要推送的信息保存至对象，以供队列推送
 */
public class PushBean {

    long pushTime;
    double price;

    int type;

    public PushBean(long pushTime, double price, int type) {
        this.pushTime = pushTime;
        this.price = price;
        this.type = type;
    }
}
