package ru.mail.polis.service.sachuk.ilya;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.lsm.DAO;
import ru.mail.polis.service.Service;
import ru.mail.polis.service.sachuk.ilya.replication.Coordinator;
import ru.mail.polis.service.sachuk.ilya.replication.ReplicationInfo;
import ru.mail.polis.service.sachuk.ilya.sharding.Node;
import ru.mail.polis.service.sachuk.ilya.sharding.NodeManager;
import ru.mail.polis.service.sachuk.ilya.sharding.NodeRouter;
import ru.mail.polis.service.sachuk.ilya.sharding.VNodeConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServiceImpl extends HttpServer implements Service {
    private final Logger logger = LoggerFactory.getLogger(ServiceImpl.class);

    private static final String ENTITY_PATH = "/v0/entity";
    private static final String STATUS_PATH = "/v0/status";

    private final EntityRequestHandler entityRequestHandler;
    private final RequestPoolExecutor requestPoolExecutor = new RequestPoolExecutor(
            new ExecutorConfig(16, 1000)
    );

    private final NodeManager nodeManager;
    private final Node node;
    private final Set<String> topology;
    private final Coordinator coordinator;

    public ServiceImpl(int port, DAO dao, Set<String> topology) throws IOException {
        super(configFrom(port));

        this.topology = topology;
        this.entityRequestHandler = new EntityRequestHandler(dao);
        this.node = new Node(port);
        this.nodeManager = new NodeManager(topology, new VNodeConfig(), node);
        NodeRouter nodeRouter = new NodeRouter(nodeManager);

        this.coordinator = new Coordinator(nodeManager, nodeRouter, entityRequestHandler, node);

        logger.info("Node with port " + port + " is started");
    }

    private static HttpServerConfig configFrom(int port) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();

        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};

        return httpServerConfig;
    }

    private Response entityRequest(Request request, String id, ReplicationInfo replicationInfo) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        boolean isCoordinator = false;
        String timestamp = request.getHeader("Timestamp");
        if (timestamp == null) {
            isCoordinator = true;
            request.addHeader("Timestamp" + System.currentTimeMillis());
        } else {
            long secs = Long.parseLong(timestamp);
            logger.info(String.valueOf(new Timestamp(secs)));
        }

        //если запрос пришел от координатора, от возращаем ему респонс, чтобы он собрал всю инфу
        if (!isCoordinator) {
            logger.info("in block from coordinator");
            return entityRequestHandler.handle(request, id);
        } else {
            return coordinator.handle(replicationInfo, id, request);
        }
    }

    private Response status() {
        return new Response(Response.OK, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        if (requestPoolExecutor.isQueueFull()) {
            try {
                session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }

        requestPoolExecutor.addTask(() -> {
            String path = request.getPath();
            Response response;
            switch (path) {
                case ENTITY_PATH:
                    String id = request.getParameter("id=");
                    String replicas = request.getParameter("replicas=");

                    ReplicationInfo replicationInfo = replicas == null
                            ? ReplicationInfo.from(topology.size())
                            : ReplicationInfo.from(replicas);

                    logger.info(replicas);
                    logger.info("ask=" + replicationInfo.ask + " and from=" + replicationInfo.from);

                    if (replicationInfo.ask > replicationInfo.from || replicationInfo.ask == 0) {
                        response = new Response(Response.BAD_REQUEST, Response.EMPTY);
                        break;
                    }

                    response = entityRequest(request, id, replicationInfo);
                    break;
                case STATUS_PATH:
                    response = status();
                    break;
                default:
                    try {
                        handleDefault(request, session);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return;
            }
            try {
                session.sendResponse(response);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public synchronized void stop() {
        super.stop();

        logger.info("Service with node:" + node.port + " is closed");

        coordinator.close();
        nodeManager.close();
    }
}
