package com.jun.mqttx.broker;

import com.jun.mqttx.broker.handler.ConnectHandler;
import com.jun.mqttx.broker.handler.MessageDelegatingHandler;
import com.jun.mqttx.entity.Session;
import com.jun.mqttx.exception.AuthenticationException;
import com.jun.mqttx.exception.AuthorizationException;
import com.jun.mqttx.service.ISessionService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.mqtt.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.Optional;

/**
 * broker handler
 *
 * @author Jun
 * @date 2020-03-03 21:30
 */
@Slf4j
@ChannelHandler.Sharable
@Component
public class BrokerHandler extends SimpleChannelInboundHandler<MqttMessage> {

    /**
     * channel 群组
     */
    public static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 消息处理器
     */
    private MessageDelegatingHandler messageDelegatingHandler;

    /**
     * 会话服务
     */
    private ISessionService sessionService;

    public BrokerHandler(MessageDelegatingHandler messageDelegatingHandler, ISessionService sessionService) {
        Assert.notNull(messageDelegatingHandler, "messageDelegatingHandler can't be null");
        Assert.notNull(sessionService, "sessionService can't be null");

        this.messageDelegatingHandler = messageDelegatingHandler;
        this.sessionService = sessionService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channels.add(ctx.channel());
    }

    /**
     * 连接断开后进行如下操作:
     * <ol>
     *     <li>清理 {@link ConnectHandler#clientMap} 中保存的clientId与channelId绑定关系</li>
     *     <li>当 cleanSession = 0 时持久化 session,这样做的目的是保存 <code>Session#messageId</code>字段变化</li>
     * </ol>
     *
     * @param ctx {@link ChannelHandlerContext}
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        //获取当前会话
        Session session = (Session) ctx.channel().attr(AttributeKey.valueOf("session")).get();

        //会话状态处理
        ConnectHandler.clientMap.remove(session.getClientId());
        if (Boolean.FALSE.equals(session.getClearSession())) {
            sessionService.save(session);
        }
    }

    /**
     * 异常处理及请求分发
     *
     * @param ctx         {@link ChannelHandlerContext}
     * @param mqttMessage {@link MqttMessage}
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MqttMessage mqttMessage) {
        //异常处理
        if (mqttMessage.decoderResult().isFailure()) {
            exceptionCaught(ctx, mqttMessage.decoderResult().cause());
            return;
        }

        //消息处理
        messageDelegatingHandler.handle(ctx, mqttMessage);
    }

    /**
     * 异常处理
     *
     * @param ctx   {@link ChannelHandlerContext}
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        //主要处理 connect 消息相关异常
        MqttConnAckMessage mqttConnAckMessage = null;
        if (cause instanceof MqttIdentifierRejectedException) {
            mqttConnAckMessage = MqttMessageBuilders.connAck()
                    .sessionPresent(false)
                    .returnCode(MqttConnectReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED)
                    .build();
        } else if (cause instanceof MqttUnacceptableProtocolVersionException) {
            mqttConnAckMessage = MqttMessageBuilders.connAck()
                    .sessionPresent(false)
                    .returnCode(MqttConnectReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION)
                    .build();
        } else if (cause instanceof AuthenticationException) {
            mqttConnAckMessage = MqttMessageBuilders.connAck()
                    .sessionPresent(false)
                    .returnCode(MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD)
                    .build();
        } else if (cause instanceof AuthorizationException) {
            mqttConnAckMessage = MqttMessageBuilders.connAck()
                    .sessionPresent(false)
                    .returnCode(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED)
                    .build();
        } else if (cause instanceof IOException) {
            //连接被强制断开
            Object clientId = ctx.channel().attr(AttributeKey.valueOf("clientId")).get();
            log.error("client:{} 连接出现异常", clientId);
        } else {
            log.error("未知异常", cause);
        }

        if (mqttConnAckMessage != null) {
            ctx.writeAndFlush(mqttConnAckMessage);
        }
        ctx.close();
    }

    /**
     * 处理未通过 {@link MqttMessageType#DISCONNECT} 消息断开连接的客户端的遗嘱消息.
     * <p>
     * [MQTT-3.1.2-8]
     * If the Will Flag is set to 1 this indicates that, if the Connect request is accepted, a Will Message MUST be
     * stored on the Server and associated with the Network Connection. The Will Message MUST be published when the
     * Network Connection is subsequently closed unless the Will Message has been deleted by the Server on receipt of
     * a DISCONNECT Packet.
     *
     * @param ctx {@link ChannelHandlerContext}
     * @param evt {@link IdleStateEvent}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            if (IdleState.ALL_IDLE.equals(((IdleStateEvent) evt).state())) {
                //获取当前会话
                Session session = (Session) ctx.channel().attr(AttributeKey.valueOf("session")).get();

                //发布遗嘱消息
                Optional.ofNullable(session)
                        .map(Session::getWillMessage)
                        .ifPresent(msg -> messageDelegatingHandler.handle(ctx, msg));

                //关闭连接
                ctx.close();
            }
        }
    }
}
