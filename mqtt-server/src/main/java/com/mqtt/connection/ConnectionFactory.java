package com.mqtt.connection;


import com.mqtt.common.ChannelAttributes;
import com.mqtt.manager.SessionManager;
import com.mqtt.utils.DateUtil;
import io.netty.channel.Channel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author: chihaojie
 * @Date: 2020/1/2 14:53
 * @Version 1.0
 * @Note
 */
public class ConnectionFactory {



    public static  final ConcurrentHashMap<String,ClientConnection> connectionFactory=new ConcurrentHashMap<>(1024);





    public    ClientConnection  create(Channel channel,SessionManager sessionManager,ConnectionFactory connectionFactory) {
        ClientConnection  connection=new ClientConnection(connectionFactory,channel,sessionManager);
        //connectionFactory.put(channel.attr(ChannelAttributes.ATTR_KEY_CLIENTID).get(),connection);
        return connection;
    }

    public ClientConnection  putConnection(ClientConnection  connection){
        connectionFactory.put(connection.getChannel().attr(ChannelAttributes.ATTR_KEY_CLIENTID).get(),connection);
        return connection;
    }

    public static ClientConnection getConnection(String clientId){
        ClientConnection connection = connectionFactory.get(clientId);
        Optional.ofNullable(connection).ifPresent(c->{
            connection.setSendMessageLastestTime(DateUtil.nowTime());
        });
        return connection;
    }

    public void removeConnection(String clientId){
        connectionFactory.remove(clientId);
    }


}
