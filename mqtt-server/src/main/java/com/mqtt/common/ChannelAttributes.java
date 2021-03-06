package com.mqtt.common;

import com.mqtt.connection.ClientConnection;
import io.netty.util.AttributeKey;

/**
 * @Author: chihaojie
 * @Date: 2020/1/2 17:31
 * @Version 1.0
 * @Note
 */
public class ChannelAttributes {

    //把连接的channel的clientId取出并放置到此channel的attach中
    public static final String ATTR_CLIENTID = "ClientID";
    public static final AttributeKey<String> ATTR_KEY_CLIENTID = AttributeKey.valueOf(ATTR_CLIENTID);

    private static final String ATTR_CONNECTION = "connection";
    public static final AttributeKey<ClientConnection> ATTR_KEY_CONNECTION = AttributeKey.valueOf(ATTR_CONNECTION);


}
