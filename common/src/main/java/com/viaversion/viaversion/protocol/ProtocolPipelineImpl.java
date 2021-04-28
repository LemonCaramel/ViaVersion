/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2021 ViaVersion and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.viaversion.viaversion.protocol;

import com.google.common.collect.Sets;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.api.protocol.AbstractSimpleProtocol;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class ProtocolPipelineImpl extends AbstractSimpleProtocol implements ProtocolPipeline {
    private final UserConnection userConnection;
    /**
     * Protocol list ordered from client to server transforation with the base protocols at the end.
     */
    private List<Protocol> protocolList;
    private Set<Class<? extends Protocol>> protocolSet;

    public ProtocolPipelineImpl(UserConnection userConnection) {
        this.userConnection = userConnection;
        userConnection.getProtocolInfo().setPipeline(this);
    }

    @Override
    protected void registerPackets() {
        protocolList = new CopyOnWriteArrayList<>();
        // Create a backing set for faster contains calls with larger pipes
        protocolSet = Sets.newSetFromMap(new ConcurrentHashMap<>());

        // This is a pipeline so we register basic pipes
        Protocol baseProtocol = Via.getManager().getProtocolManager().getBaseProtocol();
        protocolList.add(baseProtocol);
        protocolSet.add(baseProtocol.getClass());
    }

    @Override
    public void init(UserConnection userConnection) {
        throw new UnsupportedOperationException("ProtocolPipeline can only be initialized once");
    }

    @Override
    public void add(Protocol protocol) {
        protocolList.add(protocol);
        protocolSet.add(protocol.getClass());
        protocol.init(userConnection);

        if (!protocol.isBaseProtocol()) {
            moveBaseProtocolsToTail();
        }
    }

    @Override
    public void add(Collection<Protocol> protocols) {
        protocolList.addAll(protocols);
        for (Protocol protocol : protocols) {
            protocol.init(userConnection);
            this.protocolSet.add(protocol.getClass());
        }

        moveBaseProtocolsToTail();
    }

    private void moveBaseProtocolsToTail() {
        // Move base Protocols to the end, so the login packets can be modified by other protocols
        List<Protocol> baseProtocols = null;
        for (Protocol protocol : protocolList) {
            if (protocol.isBaseProtocol()) {
                if (baseProtocols == null) {
                    baseProtocols = new ArrayList<>();
                }

                baseProtocols.add(protocol);
            }
        }

        if (baseProtocols != null) {
            protocolList.removeAll(baseProtocols);
            protocolList.addAll(baseProtocols);
        }
    }

    @Override
    public void transform(Direction direction, State state, PacketWrapper packetWrapper) throws Exception {
        int originalID = packetWrapper.getId();

        // Apply protocols
        packetWrapper.apply(direction, state, 0, protocolList, direction == Direction.CLIENTBOUND);
        super.transform(direction, state, packetWrapper);

        if (Via.getManager().isDebug()) {
            logPacket(direction, state, packetWrapper, originalID);
        }
    }

    private void logPacket(Direction direction, State state, PacketWrapper packetWrapper, int originalID) {
        // Debug packet
        int clientProtocol = userConnection.getProtocolInfo().getProtocolVersion();
        ViaPlatform platform = Via.getPlatform();

        String actualUsername = packetWrapper.user().getProtocolInfo().getUsername();
        String username = actualUsername != null ? actualUsername + " " : "";

        platform.getLogger().log(Level.INFO, "{0}{1} {2}: {3} (0x{4}) -> {5} (0x{6}) [{7}] {8}",
                new Object[]{
                        username,
                        direction,
                        state,
                        originalID,
                        Integer.toHexString(originalID),
                        packetWrapper.getId(),
                        Integer.toHexString(packetWrapper.getId()),
                        Integer.toString(clientProtocol),
                        packetWrapper
                });
    }

    @Override
    public boolean contains(Class<? extends Protocol> protocolClass) {
        return protocolSet.contains(protocolClass);
    }

    @Override
    public @Nullable <P extends Protocol> P getProtocol(Class<P> pipeClass) {
        for (Protocol protocol : protocolList) {
            if (protocol.getClass() == pipeClass) {
                return (P) protocol;
            }
        }
        return null;
    }

    @Override
    public List<Protocol> pipes() {
        return protocolList;
    }

    @Override
    public boolean hasNonBaseProtocols() {
        for (Protocol protocol : protocolList) {
            if (!protocol.isBaseProtocol()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void cleanPipes() {
        registerPackets();
    }
}