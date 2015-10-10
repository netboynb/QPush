package com.argo.qpush.gateway.handler;

import com.argo.qpush.core.MetricBuilder;
import com.argo.qpush.core.entity.ClientStatus;
import com.argo.qpush.core.service.ClientServiceImpl;
import com.argo.qpush.gateway.Connection;
import com.argo.qpush.gateway.keeper.ClientKeeper;
import com.argo.qpush.gateway.keeper.ConnectionKeeper;
import com.argo.qpush.protobuf.PBAPNSBody;
import com.argo.qpush.protobuf.PBAPNSEvent;
import com.argo.qpush.protobuf.PBAPNSMessage;
import com.argo.qpush.protobuf.PBAPNSUserInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by yaming_deng on 14-8-6.
 */
public class MobileMessageHandler extends ChannelInboundHandlerAdapter {

    public static final String MULTI_CLIENTS = "multi_clients";
    public static final String SYNC = "sync";
    protected static Logger logger = LoggerFactory.getLogger(MobileMessageHandler.class);

    public MobileMessageHandler(){

    }

    /**
     * 接收到新的连接
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        logger.info("channelActive: {}", ctx.channel().hashCode());
    }

    /**
     * 读取新消息 LengthFieldBasedFrameDecoder 自动解包
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (logger.isDebugEnabled()) {
            logger.info("channelRead: {}", ctx.channel().hashCode());
        }
        MetricBuilder.requestMeter.mark();

        final PBAPNSEvent pbapnsEvent;

        try {
            byte[] bytes = (byte[]) msg;
            pbapnsEvent = PBAPNSEvent.newBuilder().mergeFrom(bytes).build();
        } catch (Exception e) {
            logger.error("Invalid Data Package.", e);
            ctx.close();
            return;
        }

        ReferenceCountUtil.release(msg);

        if (logger.isDebugEnabled()){
            logger.debug("Got Message. {}", pbapnsEvent);
        }

        if (StringUtils.isEmpty(pbapnsEvent.getUserId()) || pbapnsEvent.getOp() <= 0){
            logger.error("Invalid Client!! so close connection!! ");
            ctx.close();
            return;
        }

        if (pbapnsEvent.getTypeId() == PBAPNSEvent.DeviceTypes.Android_VALUE){
            MetricBuilder.clientAndroidMeter.mark();
        }else{
            MetricBuilder.clientIOSMeter.mark();
        }

        if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.Online_VALUE){
            Connection conn = buildConnection(ctx, pbapnsEvent);
            //记录客户端
            MessageHandlerPoolTasks.instance.getExecutor().submit(new OnNewlyAddThread(pbapnsEvent));
            ack(ctx, conn, pbapnsEvent, SYNC);

        }else if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.KeepAlive_VALUE){
            //心跳
            Connection conn = buildConnection(ctx, pbapnsEvent);
            ack(ctx, conn, pbapnsEvent, SYNC);

        }else if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.Sleep_VALUE){

            final Connection conn = buildConnection(ctx, pbapnsEvent);
            if (null != conn){
                conn.setStatusId(ClientStatus.Sleep);
            }

            MessageHandlerPoolTasks.instance.getExecutor().submit(new Runnable() {

                @Override
                public void run() {

                    if (logger.isDebugEnabled()) {
                        logger.debug("Client go to sleep and close connection. {}", pbapnsEvent);
                    }

                    ClientServiceImpl.instance.updateStatus(pbapnsEvent.getUserId(), ClientStatus.Sleep);

                }
            });

        }else if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.Awake_VALUE){
            Connection conn = buildConnection(ctx, pbapnsEvent);

            if (logger.isDebugEnabled()) {
                logger.debug("Client awake and rebuild connection. {}", pbapnsEvent);
            }
            //记录客户端
            MessageHandlerPoolTasks.instance.getExecutor().submit(new OnNewlyAddThread(pbapnsEvent));
            //心跳
            ack(ctx, conn, pbapnsEvent, SYNC);

        }else if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.PushAck_VALUE){
            //推送反馈
            if (pbapnsEvent.getRead() > 0){

                MessageHandlerPoolTasks.instance.getExecutor().submit(new Runnable() {
                    @Override
                    public void run() {
                        ClientServiceImpl.instance.updateBadge(pbapnsEvent.getUserId(), pbapnsEvent.getRead() * -1);
                    }
                });

            }

            Connection conn = buildConnection(ctx, pbapnsEvent);
            ack(ctx, conn, pbapnsEvent, SYNC);

        }else if(pbapnsEvent.getOp() == PBAPNSEvent.Ops.Offline_VALUE) {
            //离线
            final Connection connection = ConnectionKeeper.remove(pbapnsEvent.getAppKey(), pbapnsEvent.getUserId());
            if (connection != null) {

                connection.close();

                if (logger.isDebugEnabled()) {
                    logger.debug("Client go offline and close connection. {}", pbapnsEvent);
                }

                MessageHandlerPoolTasks.instance.getExecutor().submit(new Runnable() {

                    @Override
                    public void run() {
                        ClientServiceImpl.instance.updateOfflineTs(pbapnsEvent.getUserId(), connection.getLastOpTime());
                    }

                });
            }

            ctx.close();

        }
    }

    private Connection buildConnection(ChannelHandlerContext ctx, PBAPNSEvent pbapnsEvent) {
        Connection conn = ConnectionKeeper.get(pbapnsEvent.getAppKey(), pbapnsEvent.getUserId());
        boolean newOne = true;
        if (null != conn){
            if (!conn.getToken().equalsIgnoreCase(pbapnsEvent.getToken())) {
                ConnectionKeeper.remove(pbapnsEvent.getAppKey(), pbapnsEvent.getUserId());
                //只有设备标示不一样才算是重复登录
                logger.error("你已经在线了!. KickOff. current conn={}, conn={}", ctx, conn);
                ack(ctx, conn, pbapnsEvent, MULTI_CLIENTS);
                newOne = true;
            } else if (conn.getContext().channel().hashCode() != ctx.channel().hashCode()){
//                // 不是同一个Channel的话，就使用新的
//                logger.warn("Client is using different channel. old={}, new={}", conn.getContext().channel(), ctx.channel());
//                ConnectionKeeper.remove(pbapnsEvent.getAppKey(), pbapnsEvent.getUserId());
//                //conn.close();
//                conn = null;
//                newOne = true;
                newOne = false;
                conn.setStatusId(ClientStatus.Online);
            }else{
                newOne = false;
                conn.setStatusId(ClientStatus.Online);
            }
        }

        if (newOne){
            logger.debug("Got Connection from. userId={}, ip={}",  pbapnsEvent.getUserId(), ctx.channel().remoteAddress());
            conn = new Connection(ctx);
            conn.setUserId(pbapnsEvent.getUserId());
            conn.setAppKey(pbapnsEvent.getAppKey());
            conn.setToken(pbapnsEvent.getToken());
            ConnectionKeeper.add(pbapnsEvent.getAppKey(), pbapnsEvent.getUserId(), conn);
        }

        return conn;
    }

    private void ack(final ChannelHandlerContext ctx, final Connection cc, final PBAPNSEvent event, final String result){
        if (cc==null || cc.getContext() == null){
            return;
        }

        PBAPNSMessage.Builder builder = PBAPNSMessage.newBuilder();
        builder.setAps(PBAPNSBody.newBuilder().setAlert("ack").setBadge(0));

        PBAPNSUserInfo.Builder infoBuilder = PBAPNSUserInfo.newBuilder().setKey("msg").setValue(result);
        builder.addUserInfo(infoBuilder);

        infoBuilder = PBAPNSUserInfo.newBuilder().setKey("kindId").setValue(SYNC);
        builder.addUserInfo(infoBuilder);

        byte[] bytes = builder.build().toByteArray();

        final ByteBuf data = ctx.alloc().buffer(bytes.length); // (2)
        data.writeBytes(bytes);

        final ChannelFuture cf = cc.getContext().writeAndFlush(data);
        cf.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                if(cf.cause() != null){
                    logger.error("Send Error.", cf.cause());
                    cc.close();
                }else{
                    logger.debug(String.format("Send Response %s to %s[%s][%s] Done", result, event.getUserId(), cc.getContext().channel().remoteAddress(), event.getOp()));
                }
            }
        });
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        logger.info("channelReadComplete: {}", ctx.channel().hashCode());
        ctx.flush();
    }

    /**
     * 连接异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        lostConnection(ctx);
        logger.error("exceptionCaught: {}", ctx.channel().hashCode(), cause);
        ctx.close();
    }

    /**
     * 连接断开，移除连接影射，客户端发起重连
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx)
            throws Exception {
        logger.info("channelInactive: {}", ctx.channel().hashCode());
        lostConnection(ctx);
    }

    private void lostConnection(ChannelHandlerContext ctx) {
        logger.info("lost Connection: {}", ctx.channel());
        final Connection connection = ConnectionKeeper.get(ctx.channel().hashCode());
        if (null != connection){
            connection.close();
            ClientKeeper.remove(connection.getAppKey(), connection.getUserId());
            MessageHandlerPoolTasks.instance.getExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    ClientServiceImpl.instance.updateOfflineTs(connection.getUserId(), connection.getLastOpTime());
                }
            });

        }

        ConnectionKeeper.remove(ctx.channel().hashCode());
    }

}
