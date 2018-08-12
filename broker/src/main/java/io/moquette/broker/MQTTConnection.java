package io.moquette.broker;

import io.moquette.server.netty.NettyUtils;
import io.moquette.spi.security.IAuthenticator;
import io.netty.channel.Channel;
import io.netty.handler.codec.mqtt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static io.netty.channel.ChannelFutureListener.CLOSE_ON_FAILURE;
import static io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.*;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;

class MQTTConnection {

    private static final Logger LOG = LoggerFactory.getLogger(MQTTConnection.class);

    private final Channel channel;
    private BrokerConfiguration brokerConfig;
    private IAuthenticator authenticator;
    private SessionRegistry sessionRegistry;

    MQTTConnection(Channel channel) {
        this.channel = channel;
    }

    void handleMessage(MqttMessage msg) {
        MqttMessageType messageType = msg.fixedHeader().messageType();
        LOG.debug("Processing MQTT message, type: {}", messageType);
        switch (messageType) {
            case CONNECT:
                processConnect((MqttConnectMessage) msg);
                break;
//            case SUBSCRIBE:
//                m_processor.processSubscribe(channel, (MqttSubscribeMessage) msg);
//                break;
//            case UNSUBSCRIBE:
//                m_processor.processUnsubscribe(channel, (MqttUnsubscribeMessage) msg);
//                break;
//            case PUBLISH:
//                m_processor.processPublish(channel, (MqttPublishMessage) msg);
//                break;
//            case PUBREC:
//                m_processor.processPubRec(channel, msg);
//                break;
//            case PUBCOMP:
//                m_processor.processPubComp(channel, msg);
//                break;
//            case PUBREL:
//                m_processor.processPubRel(channel, msg);
//                break;
//            case DISCONNECT:
//                m_processor.processDisconnect(channel);
//                break;
//            case PUBACK:
//                m_processor.processPubAck(channel, (MqttPubAckMessage) msg);
//                break;
            case PINGREQ:
                MqttFixedHeader pingHeader = new MqttFixedHeader(
                        MqttMessageType.PINGRESP,
                        false,
                        AT_MOST_ONCE,
                        false,
                        0);
                MqttMessage pingResp = new MqttMessage(pingHeader);
                channel.writeAndFlush(pingResp).addListener(CLOSE_ON_FAILURE);
                break;
            default:
                LOG.error("Unknown MessageType: {}", messageType);
                break;
        }
    }

    private void processConnect(MqttConnectMessage msg) {
        MqttConnectPayload payload = msg.payload();
        String clientId = payload.clientIdentifier();
        final String username = payload.userName();
        LOG.debug("Processing CONNECT message. CId={}, username={}", clientId, username);

        if (isNotProtocolVersion(msg, MqttVersion.MQTT_3_1) && isNotProtocolVersion(msg, MqttVersion.MQTT_3_1_1)) {
            LOG.error("MQTT protocol version is not valid. CId={}", clientId);
            abortConnection(CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION);
            return;
        }
        final boolean cleanSession = msg.variableHeader().isCleanSession();
        if (clientId == null || clientId.length() == 0) {
            if (!brokerConfig.allowZeroByteClientId) {
                LOG.error("Broker doesn't permit MQTT client ID empty. Username={}", username);
                abortConnection(CONNECTION_REFUSED_IDENTIFIER_REJECTED);
                return;
            }

            if (!cleanSession) {
                LOG.error("MQTT client ID cannot be empty for persistent session. Username={}", username);
                abortConnection(CONNECTION_REFUSED_IDENTIFIER_REJECTED);
                return;
            }

            // Generating client id.
            clientId = UUID.randomUUID().toString().replace("-", "");
            LOG.info("Client has connected with server generated id={}, username={}", clientId, username);
        }

        if (!login(msg, clientId)) {
            abortConnection(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD);
            channel.close().addListener(CLOSE_ON_FAILURE);
            return;
        }

        sessionRegistry.bindToSession(this, msg);

    }

    private boolean isNotProtocolVersion(MqttConnectMessage msg, MqttVersion version) {
        return msg.variableHeader().version() != version.protocolLevel();
    }

    private void abortConnection(MqttConnectReturnCode returnCode) {
        MqttConnAckMessage badProto = connAck(returnCode, false);
        channel.writeAndFlush(badProto).addListener(FIRE_EXCEPTION_ON_FAILURE);
        channel.close().addListener(CLOSE_ON_FAILURE);
    }

    private MqttConnAckMessage connAck(MqttConnectReturnCode returnCode, boolean sessionPresent) {
        MqttFixedHeader mqttFixedHeader = new MqttFixedHeader(MqttMessageType.CONNACK, false, MqttQoS.AT_MOST_ONCE,
            false, 0);
        MqttConnAckVariableHeader mqttConnAckVariableHeader = new MqttConnAckVariableHeader(returnCode, sessionPresent);
        return new MqttConnAckMessage(mqttFixedHeader, mqttConnAckVariableHeader);
    }

    private boolean login(MqttConnectMessage msg, final String clientId) {
        // handle user authentication
        if (!msg.variableHeader().hasUserName() && !brokerConfig.allowAnonymous) {
            LOG.error("Client didn't supply any credentials and MQTT anonymous mode is disabled. CId={}", clientId);
            return false;
        }

        if (!msg.variableHeader().hasPassword() && !brokerConfig.allowAnonymous) {
            LOG.error("Client didn't supply any password and MQTT anonymous mode is disabled CId={}", clientId);
            return false;
        }
        byte[] pwd = msg.payload().passwordInBytes();
        final String login = msg.payload().userName();
        if (!authenticator.checkValid(clientId, login, pwd)) {
            LOG.error("Authenticator has rejected the MQTT credentials CId={}, username={}", clientId, login);
            return false;
        }
        NettyUtils.userName(channel, login);
        return true;
    }

    void handleConnectionLost() {
        String clientID = NettyUtils.clientID(channel);
        if (clientID != null && !clientID.isEmpty()) {
            LOG.info("Notifying connection lost event. MqttClientId = {}", clientID);
            m_processor.processConnectionLost(clientID, channel);
        }
        channel.close().addListener(CLOSE_ON_FAILURE);
    }
}