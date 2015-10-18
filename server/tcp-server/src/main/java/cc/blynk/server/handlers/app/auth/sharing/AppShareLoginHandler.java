package cc.blynk.server.handlers.app.auth.sharing;

import cc.blynk.common.enums.Command;
import cc.blynk.common.enums.Response;
import cc.blynk.common.model.messages.ResponseMessage;
import cc.blynk.common.model.messages.protocol.appllication.sharing.ShareLoginMessage;
import cc.blynk.common.utils.ServerProperties;
import cc.blynk.server.dao.ReportingDao;
import cc.blynk.server.dao.SessionDao;
import cc.blynk.server.dao.UserDao;
import cc.blynk.server.exceptions.IllegalCommandException;
import cc.blynk.server.handlers.DefaultReregisterHandler;
import cc.blynk.server.handlers.app.AppShareHandler;
import cc.blynk.server.handlers.app.auth.AppLoginHandler;
import cc.blynk.server.handlers.app.auth.RegisterHandler;
import cc.blynk.server.handlers.common.UserNotLoggerHandler;
import cc.blynk.server.handlers.hardware.auth.HandlerState;
import cc.blynk.server.model.auth.Session;
import cc.blynk.server.model.auth.User;
import io.netty.channel.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handler responsible for managing apps sharing login messages.
 *
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
@ChannelHandler.Sharable
public class AppShareLoginHandler extends SimpleChannelInboundHandler<ShareLoginMessage> implements DefaultReregisterHandler {

    private static final Logger log = LogManager.getLogger(AppShareLoginHandler.class);

    private final ServerProperties props;
    private final UserDao userDao;
    private final SessionDao sessionDao;
    private final ReportingDao reportingDao;

    public AppShareLoginHandler(ServerProperties props, UserDao userDao, SessionDao sessionDao, ReportingDao reportingDao) {
        this.props = props;
        this.userDao = userDao;
        this.sessionDao = sessionDao;
        this.reportingDao = reportingDao;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ShareLoginMessage message) throws Exception {
        //warn: split may be optimized
        String[] messageParts = message.body.split(" ");

        if (messageParts.length < 2) {
            throw new IllegalCommandException("Wrong income message format.", message.id);
        } else {
            String osType = null;
            String version = null;
            if (messageParts.length == 4) {
                osType = messageParts[2];
                version = messageParts[3];
            }
            appLogin(ctx, message.id, messageParts[0], messageParts[1], osType, version);
        }
    }

    private void appLogin(ChannelHandlerContext ctx, int messageId, String username, String token, String osType, String version) {
        String userName = username.toLowerCase();

        User user = userDao.sharedTokenManager.getUserByToken(token);

        if (user == null || !user.getName().equals(userName)) {
            log.debug("Share token is invalid. Token '{}', '{}'", token, ctx.channel().remoteAddress());
            ctx.writeAndFlush(new ResponseMessage(messageId, Command.RESPONSE, Response.INVALID_TOKEN));
            return;
        }

        Integer dashId = UserDao.getDashIdByToken(user.dashShareTokens, token, messageId);

        cleanPipeline(ctx.pipeline());
        ctx.pipeline().addLast(new AppShareHandler(props, userDao, sessionDao, reportingDao, new HandlerState(dashId, user, token)));

        Session session = sessionDao.getSessionByUser(user, ctx.channel().eventLoop());

        if (session.initialEventLoop != ctx.channel().eventLoop()) {
            log.debug("Re registering app channel. {}", ctx.channel());
            reRegisterChannel(ctx, session, channelFuture -> completeLogin(ctx.channel(), session, user.name));
        } else {
            completeLogin(ctx.channel(), session, user.name);
        }
    }

    private void completeLogin(Channel channel, Session session, String userName) {
        session.appChannels.add(channel);
        log.info("Shared {} app joined.", userName);
    }

    private void cleanPipeline(ChannelPipeline pipeline) {
        pipeline.remove(this);
        pipeline.remove(UserNotLoggerHandler.class);
        pipeline.remove(RegisterHandler.class);
        pipeline.remove(AppLoginHandler.class);
    }

}
