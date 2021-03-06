package com.mqtt.connection;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.mqtt.message.ClientSub;
import com.mqtt.message.Qos2Message;
import com.mqtt.message.WaitingAckQos1PublishMessage;
import com.mqtt.utils.CompellingUtil;
import com.mqtt.utils.StrUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader.from;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;

/**
 * @Author: chihaojie
 * @Date: 2020/1/9 16:59
 * @Version 1.0
 * @Note 邮递员
 */
public class PostMan {




    //订阅队列
    //每个主题对应的客户端
    public final  static ConcurrentMap<String ,List<ClientSub>> topicSubers=new ConcurrentHashMap<>();

    private final static ConcurrentMap<String,WaitingAckQos1PublishMessage> waitingAckPubs = new ConcurrentHashMap<String,WaitingAckQos1PublishMessage>();


    private final static  ConcurrentHashMap<Integer,Qos2Message>  notRecPubsMap=new ConcurrentHashMap<>(128);

    private final static  ConcurrentHashMap<Integer,Qos2Message>  notCompRelsMap=new ConcurrentHashMap<>(128);

    private static final AtomicInteger lastPacketId=new AtomicInteger(1);

    //任务调度线程池
    private final static ScheduledExecutorService  scheduler = Executors.newScheduledThreadPool(1);


    static {

        scheduler.scheduleAtFixedRate(()->{
            //重发pub
            if(null !=notRecPubsMap && !notRecPubsMap.isEmpty()){
                notRecPubsMap.forEach((k,v)->{
                    System.out.println("=========定时任务重发，Pub消息=============");
                    resendPubMessageWhenNoRecAcked(v);

                });
            }
            //重发rel
            if(null !=notCompRelsMap && !notCompRelsMap.isEmpty()){
                notCompRelsMap.forEach((k,v)->{
                    resendPubRelMsg(v);
                });
            }
            //输出当前的连接数
            System.out.println("===========当前连接的设备数================");
            System.out.println(ConnectionFactory.connectionFactory.size());
        },1,1,TimeUnit.SECONDS);
    }

    private static void resendPubMessageWhenNoRecAcked(Qos2Message v) {
        System.out.println("===============重发的老pub消息===========");
        System.out.println(JSONObject.toJSONString(v));
        Qos2Message qos2Message = notRecPubsMap.get(v.getMessageId());
        MqttFixedHeader pubFixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, true,
                MqttQoS.EXACTLY_ONCE, false, 0);
        MqttPublishVariableHeader publishVariableHeader = new MqttPublishVariableHeader(qos2Message.getTopic(), qos2Message.getMessageId());
        MqttPublishMessage pubMsg = new MqttPublishMessage(pubFixedHeader, publishVariableHeader, StrUtil.String2ByteBuf(qos2Message.getContent()));
        ClientConnection connection = ConnectionFactory.getConnection(v.getClientId());
        Optional.ofNullable(connection).ifPresent(c->{
            connection.getChannel().writeAndFlush(pubMsg);
        });
    }


    /**
     * 获取本次的packetId
     */
    private static final Integer  getNextPacketId(){
        return    lastPacketId.getAndIncrement();
    }



    public static  Boolean sendConnAck(String clientId,MqttConnectMessage mqttMessage){
       final boolean sessionPresent;
        boolean cleanSession = mqttMessage.variableHeader().isCleanSession();
        if(cleanSession){
            sessionPresent=false;
        }else {
            //判断是否存在旧会话
            //如果有，则值为true,否则为false
            sessionPresent=false;
        }
        ClientConnection connection = ConnectionFactory.getConnection(clientId);
        Optional.ofNullable(connection).ifPresent(conn->{
            MqttFixedHeader mqttFixedHeader = new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE,
                    false, 0);
            MqttConnAckVariableHeader mqttConnAckVariableHeader = new MqttConnAckVariableHeader(MqttConnectReturnCode.CONNECTION_ACCEPTED, sessionPresent);
            MqttConnAckMessage  connAckMessage= new MqttConnAckMessage(mqttFixedHeader, mqttConnAckVariableHeader);
            connection.getChannel().writeAndFlush(connAckMessage);
        });
        return true;
    }


    public static void subAck(String clientId, MqttSubscribeMessage mqttMessage, List<Integer> qos) {
        ClientConnection connection = ConnectionFactory.getConnection(clientId);
        Optional.ofNullable(connection).ifPresent(conn->{
            //发送subAck响应
            MqttFixedHeader  subAckFixedHeader=new MqttFixedHeader(MqttMessageType.SUBACK,
                    false,MqttQoS.AT_LEAST_ONCE,false,0);
            //把sub报文中的messageId取出，然后使用它构造一个subAck的可变报头
            MqttMessageIdVariableHeader subAckVHeader = MqttMessageIdVariableHeader.from(mqttMessage.variableHeader().messageId());
            //这里设定为： 请求多大给多大
            MqttSubAckPayload payload = new MqttSubAckPayload(qos);
            MqttSubAckMessage subAckMessage = new MqttSubAckMessage(subAckFixedHeader, subAckVHeader, payload);
            connection.getChannel().writeAndFlush(subAckMessage);
        });
    }

    /**
     * 放入到订阅队列中
     */
      synchronized    public static  List<Integer>  add2TopicSubers(String clientId,MqttSubscribeMessage  subMsg){
        System.out.println("================订阅消息=================");
        System.out.println(JSONObject.toJSONString(subMsg));
        MqttMessageIdVariableHeader subVarHeader = subMsg.variableHeader();
        System.out.println(subVarHeader.toString());
        MqttSubscribePayload subscribePayload = subMsg.payload();
        List<MqttTopicSubscription>  topicList = subscribePayload.topicSubscriptions();
        List<Integer>  grantedSubQos=new ArrayList<>(5);
        Optional.ofNullable(topicList).ifPresent(mts->{
             topicList.forEach(sub->{
                 List<ClientSub> topicSubList = topicSubers.get(sub.topicName());
                 grantedSubQos.add(sub.qualityOfService().value());
                 if(null !=topicSubList && !topicSubList.isEmpty()){
                     ClientSub  clientSub=new ClientSub();
                     clientSub.setClientId(clientId);
                     clientSub.setSubQos(sub.qualityOfService());
                     topicSubList.add(clientSub);
                     topicSubers.put(sub.topicName(),topicSubList);
                 }else {
                     List<ClientSub> newTopicSub= Lists.newArrayList();
                     ClientSub  clientSub=new ClientSub();
                     clientSub.setClientId(clientId);
                     clientSub.setSubQos(sub.qualityOfService());
                     newTopicSub.add(clientSub);
                     topicSubers.put(sub.topicName(),newTopicSub);
                 }
             });
        });
        System.out.println(JSONObject.toJSONString(topicSubers));
        return grantedSubQos;
    }


    public static void unsubAck(String clientId, MqttUnsubscribeMessage mqttMessage) {
        ClientConnection connection = ConnectionFactory.getConnection(clientId);
        Optional.ofNullable(connection).ifPresent(conn->{
            //构造报文
            MqttFixedHeader  unsubAckFixed=new MqttFixedHeader(MqttMessageType.UNSUBACK,
                    false,
                    MqttQoS.AT_MOST_ONCE,
                    false,
                    2);
            MqttMessageIdVariableHeader  unsubAckVh=MqttMessageIdVariableHeader.from(mqttMessage.variableHeader().messageId());
            //
            MqttUnsubAckMessage  unsubAckMessage=new MqttUnsubAckMessage(unsubAckFixed,unsubAckVh);
            //返回响应
            conn.getChannel().writeAndFlush(unsubAckMessage);
        });

    }

   synchronized public static void unsub(String clientId, MqttUnsubscribeMessage mqttMessage) {
        MqttUnsubscribePayload payload = mqttMessage.payload();
        List<String> topics = payload.topics();
        if(topics==null || topics.isEmpty()){
            return;
        }
        topics.forEach(t->{
            List<Integer>  tags=Lists.newArrayList();
            List<ClientSub> topicSubList = topicSubers.get(t.trim());
            Optional.ofNullable(topicSubList).ifPresent(tsl->{
                for (int i = 0; i < topicSubList.size(); i++) {
                     ClientSub tse=topicSubList.get(i);
                    if(tse.getClientId().equals(clientId)){
                        tags.add(i);
                    }
                }
            });
            Optional.ofNullable(tags).ifPresent(tg->{
                tags.forEach(index->{
                    topicSubList.remove(index);
                });
            });
            //重新放入
            topicSubers.put(t.trim(),topicSubList);
        });

    }

  synchronized   public static void dipatchQos0PubMsg(MqttPublishMessage mqttMessage) {
        String topicName = mqttMessage.variableHeader().topicName();
        //获取订阅者
        Optional.ofNullable(topicName).ifPresent(tn->{
            //获取订阅者
            List<ClientSub> topicSubList = topicSubers.get(tn.trim());
            System.out.println("==============订阅者有================");
            System.out.println(JSONObject.toJSONString(topicSubList));
            if(null !=topicSubList && !topicSubList.isEmpty()){
                //
                topicSubList.forEach(ts->{
                     pubQos0Msg2Suber(mqttMessage,ts,getNextPacketId());
                });
            }
        });
    }

    private static void pubQos0Msg2Suber(MqttPublishMessage pubMsg, ClientSub ts, Integer nextPacketId) {
        String content = StrUtil.ByteBuf2String(pubMsg.payload());
        //String  str = new String(msgBody.array(), msgBody.arrayOffset() + msgBody.readerIndex(), msgBody.readableBytes(),CharsetUtil.UTF_8);
        System.out.println("=======pub的payload===========");
        System.out.println(content);
        //发送消
        ClientConnection connection = ConnectionFactory.getConnection(ts.getClientId());
        System.out.println(JSONObject.toJSONString(connection));
        Optional.ofNullable(connection).ifPresent(c->{
            System.out.println("===========转发消息================");
            MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(pubMsg.variableHeader().topicName(), nextPacketId);
            //retainedDuplicate=duplicate+retained, retained即：针对当前ByteBuf多增加一次引用计数
            //final ByteBuf copiedPayload = pubMsg.payload().retainedDuplicate();
            MqttPublishMessage  tpubMsg=new MqttPublishMessage(fixedHeader,varHeader,StrUtil.String2ByteBuf(content));
            System.out.println("=============已转发的pub消息==============");
            System.out.println(tpubMsg.toString());
            connection.getChannel().writeAndFlush(tpubMsg);
            System.out.println(content);
            //System.out.println(pubMsg.release());
            //System.out.println(tpubMsg.release());
            //tpubMsg.release();
        });

    }

    public static void dipatchQos1PubMsg(MqttPublishMessage mqttMessage, String clientId) {
           //转发Qos1的消息
         String topicName = mqttMessage.variableHeader().topicName();
        List<ClientSub> clientSubs = topicSubers.get(topicName);
        Optional.ofNullable(clientSubs).ifPresent(css->{
            //转发消息
            //遍历进行发布
            clientSubs.forEach(cs->{
                 //获取连接进行发送
                ClientConnection connection = ConnectionFactory.getConnection(cs.getClientId());
                System.out.println(JSONObject.toJSONString(connection));
                Optional.ofNullable(connection).ifPresent(c->{
                    MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, false, MqttQoS.AT_MOST_ONCE, false, 0);
                    Integer messageId=getNextPacketId();
                    MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(mqttMessage.variableHeader().topicName(), messageId);
                    //retainedDuplicate=duplicate+retained, retained即：针对当前ByteBuf多增加一次引用计数
                    final ByteBuf copiedPayload = mqttMessage.payload().retainedDuplicate();
                    MqttPublishMessage  tpubMsg=new MqttPublishMessage(fixedHeader,varHeader,copiedPayload);
                    connection.getChannel().writeAndFlush(tpubMsg);
                    //发送后，存入待确认队列
                    WaitingAckQos1PublishMessage   waitingAck=new WaitingAckQos1PublishMessage();
                    waitingAck.setClientId(clientId);
                    waitingAck.setTopic(topicName);
                    waitingAck.setMessageId(messageId);
                    waitingAck.setPayload(StrUtil.ByteBuf2String(mqttMessage.payload()));
                    waitingAckPubs.put(clientId,waitingAck);
                });
            });
        });
        //发出去的Qos1的消息，必须要收到回复确认，否则就一直重发
        //3秒后进行重发
        ClientConnection connection = ConnectionFactory.getConnection(clientId);
        Optional.ofNullable(clientId).ifPresent(c->{
            Channel channel = connection.getChannel();
            if(channel!=null){
                channel.eventLoop().scheduleAtFixedRate(()->{
                    //重发消息
                    waitingAckPubs.forEach((k,v)->{
                        //进行发送
                        WaitingAckQos1PublishMessage poll = waitingAckPubs.get(k);
                        resendQos1PubMsg(poll);
                    });
                },3,3,TimeUnit.SECONDS);

            }
        });
        //重发的消息的messageId
        //把新队列放入
       ReferenceCountUtil.release(mqttMessage);
    }

    private static void resendQos1PubMsg(WaitingAckQos1PublishMessage poll) {
          try{
              ClientConnection connection = ConnectionFactory.getConnection(poll.getClientId());
              MqttFixedHeader fixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, true, MqttQoS.AT_LEAST_ONCE, false, 0);
              MqttPublishVariableHeader varHeader = new MqttPublishVariableHeader(poll.getTopic(), poll.getMessageId());
              //retainedDuplicate=duplicate+retained, retained即：针对当前ByteBuf多增加一次引用计数
              ByteBuf payload=StrUtil.String2ByteBuf(poll.getPayload());
              MqttPublishMessage  resendPubMsg=new MqttPublishMessage(fixedHeader,varHeader,payload);
              connection.getChannel().writeAndFlush(resendPubMsg);
          }catch (Exception e){
              e.printStackTrace();
          }
    }

    public static void handlePubAckMsg(MqttPubAckMessage mqttMessage, String clientId) {
        int messageId = mqttMessage.variableHeader().messageId();
        WaitingAckQos1PublishMessage publishMessage = waitingAckPubs.get(messageId);
        Optional.ofNullable(publishMessage).ifPresent(p->{
            waitingAckPubs.remove(messageId);
        });

    }

    public static void dipatchQos2PubMsg(Qos2Message qos2Message) {
           //转发Qos2级别的消息
           //1、找到匹配的订阅者
           //2、生成pub消息并转发
           //3、没有收到rec时，重发
          List<String> suberClients=matchTopic(qos2Message);
          System.out.println("=============匹配的订阅客户端======");
          System.out.println(JSONObject.toJSONString(suberClients));
          //进行转发
         suberClients.forEach(client->{
              //重发
             ClientConnection connection = ConnectionFactory.getConnection(client);
             Channel channel = connection.getChannel();
             MqttPublishMessage pubComp= createQos2PubMsg(qos2Message);
             channel.writeAndFlush(pubComp);
             //加入rec等待队列
             Qos2Message waitRec=new Qos2Message(pubComp.variableHeader().packetId(),qos2Message.getTopic(),qos2Message.getQos(),qos2Message.getContent());
             waitRec.setClientId(client);
             notRecPubsMap.put(waitRec.getMessageId(),waitRec);
             System.out.println("===========放入notRecPubsMap=========");
             System.out.println(JSONObject.toJSONString(waitRec));
         });
    }

    private static MqttPublishMessage createQos2PubMsg(Qos2Message qos2Message) {
        MqttFixedHeader pubFixedHeader = new MqttFixedHeader(MqttMessageType.PUBLISH, true,
                MqttQoS.EXACTLY_ONCE, false, 0);
        MqttPublishVariableHeader publishVariableHeader = new MqttPublishVariableHeader(qos2Message.getTopic(), getNextPacketId());
        MqttPublishMessage publishMessage = new MqttPublishMessage(pubFixedHeader, publishVariableHeader, StrUtil.String2ByteBuf(qos2Message.getContent()));
        return publishMessage;
    }

    private static List<String> matchTopic(Qos2Message qos2Message) {
        String topic = qos2Message.getTopic();
        List<ClientSub> clientSubs = topicSubers.get(topic);
        if(null !=clientSubs && !clientSubs.isEmpty()){
            List<String> collect = clientSubs.stream().map(ClientSub::getClientId).collect(Collectors.toList());
            return collect;
        }
        return null;
    }

    /**
     * 处理pubRec报文
     * @param recMsg
     * @param clientId
     */
    public static void processPubRecMsg(MqttMessage recMsg, String clientId) {
        System.out.println("=============移出前==================");
        System.out.println(JSONObject.toJSONString(notRecPubsMap));
        //移出等待rec队列
        final int messageID = ((MqttMessageIdVariableHeader) recMsg.variableHeader()).messageId();
        Qos2Message qos2Message = notRecPubsMap.remove(messageID);
        System.out.println("=============移出后==================");
        System.out.println(JSONObject.toJSONString(notRecPubsMap));
        //响应pubRel报文
        sendPubRelToClient(messageID,clientId);
        notCompRelsMap.put(messageID,qos2Message);
    }

    private static void sendPubRelToClient(int messageID, String clientId) {
        //响应Rel
        ClientConnection connection = ConnectionFactory.getConnection(clientId);
        Optional.ofNullable(connection).ifPresent(c->{
            MqttFixedHeader recFixedHeader = new MqttFixedHeader(MqttMessageType.PUBREL, false,
                    MqttQoS.EXACTLY_ONCE, false, 2);
            MqttPubAckMessage pubRelMessage = new MqttPubAckMessage(recFixedHeader, from(messageID));
            connection.getChannel().writeAndFlush(pubRelMessage);
        });
    }

    private static void resendPubRelMsg(Qos2Message v) {
        ClientConnection connection = ConnectionFactory.getConnection(v.getClientId());
        Optional.ofNullable(connection).ifPresent(c->{
            MqttFixedHeader recFixedHeader = new MqttFixedHeader(MqttMessageType.PUBREL, true,
                    MqttQoS.EXACTLY_ONCE, false, 2);
            MqttPubAckMessage pubRelMessage = new MqttPubAckMessage(recFixedHeader, from(v.getMessageId()));
            connection.getChannel().writeAndFlush(pubRelMessage);
        });
    }

    public static void processPubCompMsg(int messageId) {
        //从等待comp响应的列表中移出相应的报文
        notCompRelsMap.remove(messageId);
    }
}
