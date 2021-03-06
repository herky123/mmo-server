package org.mmo.cluster.service;


import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.ScheduledFuture;
import org.mmo.common.constant.ServerType;
import org.mmo.common.constant.ThreadType;
import org.mmo.engine.script.ScriptService;
import org.mmo.engine.server.ServerInfo;
import org.mmo.engine.server.ServerProperties;
import org.mmo.engine.thread.Scene.Scene;
import org.mmo.engine.thread.Scene.SceneLoop;
import org.mmo.engine.util.TimeUtil;
import org.mmo.message.ServerListRequest;
import org.mmo.message.ServerListResponse;
import org.mmo.message.ServerRegisterUpdateResponse;
import org.mmo.message.ServerServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * 服务器管理
 *
 * @author JiangZhiYong
 * @mail 359135103@qq.com
 */
@Service
public class ClusterServerService extends ServerServiceGrpc.ServerServiceImplBase implements Scene {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterServerService.class);


    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ServerProperties serverProperties;
    @Autowired
    ClusterExecutorService executorService;

    /**
     * 游戏服务器信息 serverId
     */
    private final Map<ServerType, Map<Integer, ServerInfo>> servers = new ConcurrentHashMap<>();
    /**
     * 网关服务器列表
     */
    private final Vector<ServerInfo> gateServerInfos = new Vector<>();

    private SceneLoop sceneLoop;

    private Set<ScheduledFuture<?>> fixedRateScheduledFutures = new HashSet<ScheduledFuture<?>>();


    @Override
    public SceneLoop eventLoop() {
        return sceneLoop;
    }

    @Override
    public boolean isRegistered() {
        return sceneLoop != null;
    }

    @Override
    public void register(SceneLoop sceneLoop) {
        this.sceneLoop = sceneLoop;

    }

    @Override
    public Set<ScheduledFuture<?>> getFixedRateScheduledFutures() {
        return this.fixedRateScheduledFutures;
    }


    /**
     * 初始化
     */
    @PostConstruct
    public void init() {
        LOGGER.info("服务器：{}-{} 启动...", serverProperties.getId(), serverProperties.getName());

        scriptService.init((str) -> {
            LOGGER.error("脚本加载错误:{}", str);
            System.exit(0);
        });
        executorService.registerScene(ThreadType.server.name(), this);
        this.scheduleAtFixedRate(() -> {
            update();
        }, 10, 1, TimeUnit.SECONDS);
    }


    /**
     * 定时任务
     */
    public void update() {
        long now = TimeUtil.currentTimeMillis();
        servers.forEach((k, v) -> {
            Iterator<ServerInfo> iterator = v.values().iterator();
            while (iterator.hasNext()) {
                ServerInfo serverInfo = iterator.next();
                if (serverInfo.getUpdateTime() + 6 > now) {
                    iterator.remove();
                    if (serverInfo.getServerType() == ServerType.GATE.getType()) {
                        gateServerInfos.remove(serverInfo);
                    }
                    LOGGER.info("服务器：{}-{}-{} 关闭", serverInfo.getId(), serverInfo.getName(), ServerType.valueof(serverInfo.getServerType()));
                }

            }
        });
    }

    /**
     * 获取服务器信息
     *
     * @param serverType
     * @param serverId
     * @return
     */
    public ServerInfo getServerInfo(ServerType serverType, int serverId) {
        Map<Integer, ServerInfo> serverMap = servers.get(serverType);
        if (serverMap != null) {
            return serverMap.get(serverId);
        }
        return null;
    }

    /**
     * 更新添加服务器信息
     *
     * @param serverType
     * @param serverInfo
     */
    public void addServerInfo(ServerType serverType, ServerInfo serverInfo) {
        Map<Integer, ServerInfo> serverMap = servers.get(serverType);
        if (serverMap == null) {
            serverMap = new HashMap<>();
            servers.put(serverType, serverMap);
        }
        serverMap.put(serverInfo.getId(), serverInfo);
        if (serverType == ServerType.GATE) {
            if (gateServerInfos.size() > 0) {
                for (int i = gateServerInfos.size() - 1; i >= 0; i--) {
                    if (gateServerInfos.get(i) == null || (gateServerInfos.get(i) != serverInfo
                            && gateServerInfos.get(i).getId() == serverInfo.getId())) {
                        gateServerInfos.remove(i);
                    }
                }
            }
            gateServerInfos.add(serverInfo);
            updateGateServer();
        }
    }

    /**
     * 更新gate顺序
     */
    public void updateGateServer() {
        Collections.sort(gateServerInfos, (ServerInfo s0, ServerInfo s1) -> {
            return (s0.getOnline()) - (s1.getOnline());
        });
    }

    public String getGateList() {
        if (gateServerInfos.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        gateServerInfos.forEach(s -> {
            if (s.getServerState() >= 0) {
                sb.append(s.getWwwip()).append(":").append(s.getPort()).append(";");
                LOGGER.debug("网关{} 人数{}", s.getId(), s.getOnline());
            }
        });
        String url = sb.toString();
        return url.substring(0, url.length() - 1);
    }


    /**
     * 销毁
     */
    @PreDestroy
    public void destroy() {
        LOGGER.info("服务器：{}-{} 关闭...", serverProperties.getId(), serverProperties.getName());

    }


    @Override
    public void serverRegister(org.mmo.message.ServerRegisterUpdateRequest request, StreamObserver<org.mmo.message.ServerRegisterUpdateResponse> responseObserver) {
        LOGGER.info("请求信息：{}", request.toString());
        var response = ServerRegisterUpdateResponse.newBuilder().setStatus(0).build();
        var serverInfo = request.getServerInfo();
        ServerType serverType = ServerType.valueof(serverInfo.getType());

        var info = new ServerInfo();
        info.setId(serverInfo.getId());
        info.setWwwip(serverInfo.getWwwip());
        info.setIp(serverInfo.getIp());
        info.setPort(serverInfo.getPort());
        info.setOnline(serverInfo.getOnline());
        info.setServerState(serverInfo.getState());
        info.setName(serverInfo.getName());
        info.setBelongId(serverInfo.getBelongID());
        info.setContent(serverInfo.getContent());
        info.setHttpPort(serverInfo.getHttpPort());
        info.setMaintainTime(serverInfo.getMaintainTime());
        info.setMaxUserCount(serverInfo.getMaxUserCount());
        info.setOpenTime(serverInfo.getOpenTime());
        info.setGateGamePort(serverInfo.getGamePort());
        info.setServerType(serverInfo.getType());
        info.setUpdateTime(TimeUtil.currentTimeMillis());
        addServerInfo(serverType, info);

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void serverUpdate(org.mmo.message.ServerRegisterUpdateRequest request, StreamObserver<org.mmo.message.ServerRegisterUpdateResponse> responseObserver) {
        var serverInfo = request.getServerInfo();
        ServerType serverType = ServerType.valueof(serverInfo.getType());
        ServerInfo info = getServerInfo(serverType, serverInfo.getId());
        if (info == null) {//非游戏服务器注册信息为空
            serverRegister(request, responseObserver);
            return;
        }
        info.setId(serverInfo.getId());
        info.setWwwip(serverInfo.getWwwip());
        info.setIp(serverInfo.getIp());
        info.setPort(serverInfo.getPort());
        info.setOnline(serverInfo.getOnline());
        info.setServerState(serverInfo.getState());
        info.setName(serverInfo.getName());
        info.setBelongId(serverInfo.getBelongID());
        info.setContent(serverInfo.getContent());
        info.setHttpPort(serverInfo.getHttpPort());
        info.setMaintainTime(serverInfo.getMaintainTime());
        info.setMaxUserCount(serverInfo.getMaxUserCount());
        info.setOpenTime(serverInfo.getOpenTime());
        info.setGateGamePort(serverInfo.getGamePort());
        info.setServerType(serverInfo.getType());
        info.setUpdateTime(TimeUtil.currentTimeMillis());
        if (serverType == ServerType.GATE) {
            updateGateServer();
        }

        var response = ServerRegisterUpdateResponse.newBuilder().setStatus(0).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void serverList(ServerListRequest request, StreamObserver<ServerListResponse> responseObserver) {
        ServerType serverType = ServerType.valueof(request.getType());
        if (serverType == ServerType.NONE) {
            LOGGER.warn("请求服务器类型：{}不存在", request.getType());
            return;
        }
        Map<Integer, ServerInfo> map = servers.get(serverType);
        if (map == null) {
            LOGGER.warn("服务器：{} 无实例启动", serverType.toString());
            return;
        }

        ServerListResponse.Builder builder = ServerListResponse.newBuilder();
        org.mmo.message.ServerInfo.Builder info = org.mmo.message.ServerInfo.newBuilder();
        map.forEach(((k, it) -> {
            info.setId(it.getId());
            info.setBelongID(it.getBelongId());
            info.setContent(it.getContent());
            info.setHttpPort(it.getHttpPort());
            info.setIp(it.getIp());
            info.setMaintainTime(it.getMaintainTime());
            info.setMaxUserCount(it.getMaxUserCount());
            info.setName(it.getName());
            info.setOnline(it.getOnline());
            info.setOpenTime(it.getOpenTime());
            info.setPort(it.getPort());
            info.setState(it.getServerState());
            info.setGamePort(it.getGateGamePort());
            info.setType(it.getServerType());
            if (it.getVersion() != null) {
                info.setVersion(it.getVersion());
            }
            info.setWwwip(it.getWwwip());
            builder.addServer(info.build());
            info.clear();
        }));
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
