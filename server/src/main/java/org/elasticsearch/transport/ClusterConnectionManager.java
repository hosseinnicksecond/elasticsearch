/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.AbstractRefCounted;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.util.concurrent.ListenableFuture;
import org.elasticsearch.common.util.concurrent.RunOnce;
import org.elasticsearch.core.internal.io.IOUtils;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class manages node connections within a cluster. The connection is opened by the underlying transport.
 * Once the connection is opened, this class manages the connection. This includes closing the connection when
 * the connection manager is closed.
 */
public class ClusterConnectionManager implements ConnectionManager {

    private static final Logger logger = LogManager.getLogger(ClusterConnectionManager.class);

    private final ConcurrentMap<DiscoveryNode, Transport.Connection> connectedNodes = ConcurrentCollections.newConcurrentMap();
    private final ConcurrentMap<DiscoveryNode, ListenableFuture<Void>> pendingConnections = ConcurrentCollections.newConcurrentMap();
    private final AbstractRefCounted connectingRefCounter = AbstractRefCounted.of(this::pendingConnectionsComplete);

    private final Transport transport;
    private final ConnectionProfile defaultProfile;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private final DelegatingNodeConnectionListener connectionListener = new DelegatingNodeConnectionListener();

    public ClusterConnectionManager(Settings settings, Transport transport) {
        this(ConnectionProfile.buildDefaultConnectionProfile(settings), transport);
    }

    public ClusterConnectionManager(ConnectionProfile connectionProfile, Transport transport) {
        this.transport = transport;
        this.defaultProfile = connectionProfile;
    }

    @Override
    public void addListener(TransportConnectionListener listener) {
        this.connectionListener.addListener(listener);
    }

    @Override
    public void removeListener(TransportConnectionListener listener) {
        this.connectionListener.removeListener(listener);
    }

    @Override
    public void openConnection(DiscoveryNode node, ConnectionProfile connectionProfile, ActionListener<Transport.Connection> listener) {
        ConnectionProfile resolvedProfile = ConnectionProfile.resolveConnectionProfile(connectionProfile, defaultProfile);
        internalOpenConnection(node, resolvedProfile, listener);
    }

    /**
     * Connects to a node with the given connection profile. If the node is already connected this method has no effect.
     * Once a successful is established, it can be validated before being exposed.
     * The ActionListener will be called on the calling thread or the generic thread pool.
     */
    @Override
    public void connectToNode(DiscoveryNode node, ConnectionProfile connectionProfile,
                              ConnectionValidator connectionValidator,
                              ActionListener<Void> listener) throws ConnectTransportException {
        ConnectionProfile resolvedProfile = ConnectionProfile.resolveConnectionProfile(connectionProfile, defaultProfile);
        if (node == null) {
            listener.onFailure(new ConnectTransportException(null, "can't connect to a null node"));
            return;
        }

        if (connectingRefCounter.tryIncRef() == false) {
            listener.onFailure(new IllegalStateException("connection manager is closed"));
            return;
        }

        if (connectedNodes.containsKey(node)) {
            connectingRefCounter.decRef();
            listener.onResponse(null);
            return;
        }

        final ListenableFuture<Void> currentListener = new ListenableFuture<>();
        final ListenableFuture<Void> existingListener = pendingConnections.putIfAbsent(node, currentListener);
        if (existingListener != null) {
            try {
                // wait on previous entry to complete connection attempt
                existingListener.addListener(listener);
            } finally {
                connectingRefCounter.decRef();
            }
            return;
        }

        currentListener.addListener(listener);

        // It's possible that a connection completed, and the pendingConnections entry was removed, between the calls to
        // connectedNodes.containsKey and pendingConnections.putIfAbsent above, so we check again to make sure we don't open a redundant
        // extra connection to the node. We could _just_ check here, but checking up front skips the work to mark the connection as pending.
        if (connectedNodes.containsKey(node)) {
            ListenableFuture<Void> future = pendingConnections.remove(node);
            assert future == currentListener : "Listener in pending map is different than the expected listener";
            connectingRefCounter.decRef();
            future.onResponse(null);
            return;
        }

        final RunOnce releaseOnce = new RunOnce(connectingRefCounter::decRef);
        internalOpenConnection(node, resolvedProfile, ActionListener.wrap(conn -> {
            connectionValidator.validate(conn, resolvedProfile, ActionListener.wrap(
                ignored -> {
                    assert Transports.assertNotTransportThread("connection validator success");
                    try {
                        if (connectedNodes.putIfAbsent(node, conn) != null) {
                            assert false : "redundant conection to " + node;
                            logger.debug("existing connection to node [{}], closing new redundant connection", node);
                            IOUtils.closeWhileHandlingException(conn);
                        } else {
                            logger.debug("connected to node [{}]", node);
                            try {
                                connectionListener.onNodeConnected(node, conn);
                            } finally {
                                final Transport.Connection finalConnection = conn;
                                conn.addCloseListener(ActionListener.wrap(() -> {
                                    logger.trace("unregistering {} after connection close and marking as disconnected", node);
                                    connectedNodes.remove(node, finalConnection);
                                    connectionListener.onNodeDisconnected(node, conn);
                                }));
                            }
                        }
                    } finally {
                        ListenableFuture<Void> future = pendingConnections.remove(node);
                        assert future == currentListener : "Listener in pending map is different than the expected listener";
                        releaseOnce.run();
                        future.onResponse(null);
                    }
                }, e -> {
                    assert Transports.assertNotTransportThread("connection validator failure");
                    IOUtils.closeWhileHandlingException(conn);
                    failConnectionListeners(node, releaseOnce, e, currentListener);
                }));
        }, e -> {
            assert Transports.assertNotTransportThread("internalOpenConnection failure");
            failConnectionListeners(node, releaseOnce, e, currentListener);
        }));
    }

    /**
     * Returns a connection for the given node if the node is connected.
     * Connections returned from this method must not be closed. The lifecycle of this connection is
     * maintained by this connection manager
     *
     * @throws NodeNotConnectedException if the node is not connected
     * @see #connectToNode(DiscoveryNode, ConnectionProfile, ConnectionValidator, ActionListener)
     */
    @Override
    public Transport.Connection getConnection(DiscoveryNode node) {
        Transport.Connection connection = connectedNodes.get(node);
        if (connection == null) {
            throw new NodeNotConnectedException(node, "Node not connected");
        }
        return connection;
    }

    /**
     * Returns {@code true} if the node is connected.
     */
    @Override
    public boolean nodeConnected(DiscoveryNode node) {
        return connectedNodes.containsKey(node);
    }

    /**
     * Disconnected from the given node, if not connected, will do nothing.
     */
    @Override
    public void disconnectFromNode(DiscoveryNode node) {
        Transport.Connection nodeChannels = connectedNodes.remove(node);
        if (nodeChannels != null) {
            // if we found it and removed it we close
            nodeChannels.close();
        }
    }

    /**
     * Returns the number of nodes this manager is connected to.
     */
    @Override
    public int size() {
        return connectedNodes.size();
    }

    @Override
    public Set<DiscoveryNode> getAllConnectedNodes() {
        return Collections.unmodifiableSet(connectedNodes.keySet());
    }

    @Override
    public void close() {
        internalClose(true);
    }

    @Override
    public void closeNoBlock() {
        internalClose(false);
    }

    private void internalClose(boolean waitForPendingConnections) {
        assert Transports.assertNotTransportThread("Closing ConnectionManager");
        if (closing.compareAndSet(false, true)) {
            connectingRefCounter.decRef();
            if (waitForPendingConnections) {
                try {
                    closeLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private void pendingConnectionsComplete() {
        final Iterator<Map.Entry<DiscoveryNode, Transport.Connection>> iterator = connectedNodes.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<DiscoveryNode, Transport.Connection> next = iterator.next();
            try {
                IOUtils.closeWhileHandlingException(next.getValue());
            } finally {
                iterator.remove();
            }
        }
        closeLatch.countDown();
    }

    private void internalOpenConnection(DiscoveryNode node, ConnectionProfile connectionProfile,
                                        ActionListener<Transport.Connection> listener) {
        transport.openConnection(node, connectionProfile, listener.map(connection -> {
            assert Transports.assertNotTransportThread("internalOpenConnection success");
            try {
                connectionListener.onConnectionOpened(connection);
            } finally {
                connection.addCloseListener(ActionListener.wrap(() -> connectionListener.onConnectionClosed(connection)));
            }
            if (connection.isClosed()) {
                throw new ConnectTransportException(node, "a channel closed while connecting");
            }
            return connection;
        }));
    }

    private void failConnectionListeners(DiscoveryNode node, RunOnce releaseOnce, Exception e, ListenableFuture<Void> expectedListener) {
        ListenableFuture<Void> future = pendingConnections.remove(node);
        releaseOnce.run();
        if (future != null) {
            assert future == expectedListener : "Listener in pending map is different than the expected listener";
            future.onFailure(e);
        }
    }

    @Override
    public ConnectionProfile getConnectionProfile() {
        return defaultProfile;
    }

}
